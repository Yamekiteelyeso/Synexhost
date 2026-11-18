package com.synex.host

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Persistent, fully local Minecraft hosting controller. No external apps, local ports or tokens. */
internal class LocalEngine private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var singleton: LocalEngine? = null
        fun get(ctx: Context): LocalEngine = singleton ?: synchronized(this) {
            singleton ?: LocalEngine(ctx.applicationContext).also { singleton = it }
        }
    }

    private val home = File(ctx.filesDir, "synex-host").apply { mkdirs() }
    private val serversDir = File(home, "servers").apply { mkdirs() }
    private val configs = File(home, "servers.json")
    private val specList = mutableListOf<JSONObject>()
    private val instances = ConcurrentHashMap<String, Instance>()

    private class Instance(val id: String, val dir: File) {
        @Volatile var process: Process? = null
        @Volatile var installing = false
        @Volatile var error: String? = null
        @Volatile var installStep: String = ""
        @Volatile var installProgress: Int = -1
        fun step(message: String, percent: Int = -1) {
            installStep = message
            installProgress = percent
        }
        private var seq = 0
        private val ring = ArrayDeque<Pair<Int, String>>()
        @Synchronized fun log(s: String) {
            seq++
            ring.addLast(seq to s.take(4000))
            if (ring.size > 2500) ring.removeFirst()
        }
        @Synchronized fun logs(since: Int) = LogsPage(ring.filter { it.first > since }.map { it.second }, seq)
    }

    init {
        if (configs.isFile) runCatching {
            val rows = JSONArray(configs.readText())
            for (i in 0 until rows.length()) specList.add(rows.getJSONObject(i))
        }
        if (specList.isEmpty()) {
            val def = JSONObject().put("id", "mi-boxpvp").put("name", "Mi BoxPvP")
                .put("software", "Paper").put("version", "1.20.1")
                .put("ramMb", 2048).put("port", 25565)
            specList.add(def)
            persist()
        }
        specList.forEach { spec ->
            val id = spec.getString("id")
            instances[id] = Instance(id, File(serversDir, id).apply { mkdirs() })
        }
    }

    private fun persist() {
        val tmp = File(home, "servers.json.tmp")
        tmp.writeText(JSONArray(specList).toString(2))
        if (!tmp.renameTo(configs)) {
            tmp.copyTo(configs, overwrite = true)
            tmp.delete()
        }
    }
    private fun spec(id: String): JSONObject = synchronized(specList) {
        specList.firstOrNull { it.getString("id") == id } ?: error("Servidor no encontrado")
    }
    private fun instance(id: String) = instances[id] ?: error("Servidor no encontrado")

    fun servers(): List<ServerInfo> = synchronized(specList) {
        specList.map { s ->
            val i = instance(s.getString("id"))
            ServerInfo(s.getString("id"), s.getString("name"), "Paper", s.getString("version"),
                s.getInt("ramMb"), s.getInt("port"), (File(i.dir, "paper.jar").length() > 100_000L),
                i.installing, i.process?.isAlive == true,
                File(i.dir, "eula.txt").takeIf { it.isFile }?.readText()?.lineSequence()?.any { it.trim() == "eula=true" } == true,
                i.error, i.installStep, i.installProgress)
        }
    }

    private fun urlJson(url: String): Any {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "SynexHost/0.3 https://github.com/Yamekiteelyeso/Synexhost")
            if (conn.responseCode !in 200..299) error("Error HTTP ${conn.responseCode} en PaperMC")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return when (text.trim().firstOrNull()) {
                '[' -> JSONArray(text)
                '{' -> JSONObject(text)
                else -> error("Respuesta inválida de PaperMC")
            }
        } finally { conn.disconnect() }
    }

    fun versions(): List<String> {
        val response = urlJson("https://fill.papermc.io/v3/projects/paper") as JSONObject
        val groups = response.getJSONObject("versions")
        val versions = mutableListOf<String>()
        val re = Regex("""\d{1,2}\.\d{1,2}(?:\.\d{1,2})?""")
        groups.keys().forEach { group ->
            val entries = groups.getJSONArray(group)
            for (n in 0 until entries.length()) {
                val v = entries.getString(n)
                if (re.matches(v) && v !in versions) versions.add(v)
            }
        }
        return versions
    }

    fun create(name: String, version: String, ramMb: Int): JSONObject {
        require(name.matches(Regex("""[A-Za-z0-9 ._()\-áéíóúÁÉÍÓÚñÑ]{1,48}"""))) { "Nombre inválido" }
        require(version.matches(Regex("""\d{1,2}\.\d{1,2}(?:\.\d{1,2})?"""))) { "Versión inválida" }
        require(ramMb in 512..16384 && ramMb % 256 == 0) { "RAM inválida" }
        EmbeddedRuntime.majorFor(version)
        synchronized(specList) {
            val base = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "minecraft" }
            var id = base
            var index = 2
            while (instances.containsKey(id)) id = "$base-${index++}"
            val port = (25565..25999).firstOrNull { p -> specList.none { it.getInt("port") == p } }
                ?: error("No hay puertos disponibles")
            val spec = JSONObject().put("id", id).put("name", name).put("software", "Paper")
                .put("version", version).put("ramMb", ramMb).put("port", port)
            specList.add(spec)
            instances[id] = Instance(id, File(serversDir, id).apply { mkdirs() })
            persist()
            action(id, "install") // create and download without a separate setup step
            return JSONObject().put("id", id)
        }
    }

    fun action(id: String, action: String) {
        val i = instance(id)
        when (action) {
            "install" -> {
                require(!i.installing && i.process?.isAlive != true) { "Ya hay una operación en curso" }
                i.installing = true
                i.error = null
                i.step("Preparando Java integrado")
                thread(name = "synex-install-$id", isDaemon = true) {
                    try {
                        val spec = spec(id)
                        val version = spec.getString("version")
                        val (runtime, java) = EmbeddedRuntime.prepare(ctx, version) { line ->
                            i.log(line)
                            i.step(line.removePrefix("[Synex] "))
                        }
                        i.step("Comprobando Java integrado")
                        EmbeddedRuntime.selfTest(ctx, runtime, java, i.dir) { i.log(it) }
                        i.step("Consultando builds de PaperMC")
                        downloadPaper(i, version)
                        i.step("Instalación completa", 100)
                    } catch (e: Exception) {
                        i.error = "${e.javaClass.simpleName}: ${e.message ?: "Falló la instalación"}"
                        i.step("Instalación interrumpida")
                        i.log("[ERROR] ${i.error}")
                        e.stackTrace.take(8).forEach { frame ->
                            i.log("[TRACE] $frame")
                        }
                        Log.e("SynexHost", "Installation failed for ${i.id}", e)
                    } finally { i.installing = false }
                }
            }
            "start" -> {
                require(!i.installing) { "Espera la instalación de Java y Paper" }
                require(i.process?.isAlive != true) { "Ya está encendido" }
                require(File(i.dir, "paper.jar").isFile) { "El servidor todavía no está instalado" }
                require(servers().first { it.id == id }.eulaAccepted) { "Acepta la EULA de Minecraft primero" }
                val s = spec(id)
                val (runtime, java) = EmbeddedRuntime.prepare(ctx, s.getString("version")) { i.log(it) }
                i.log("[Synex] Java integrado: ${java.absolutePath}")
                File(i.dir, "server.properties").let { props ->
                    if (!props.exists()) props.writeText("server-port=${s.getInt("port")}\nmax-players=10\nonline-mode=true\nmotd=Synex Host\n")
                }
                val ram = s.getInt("ramMb")
                val args = listOf(java.absolutePath, "-Xms512M", "-Xmx${ram}M", "-Djava.awt.headless=true", "-jar", "paper.jar", "nogui")
                val builder = ProcessBuilder(args).directory(i.dir).redirectErrorStream(true)
                builder.environment().putAll(EmbeddedRuntime.environment(ctx, runtime, i.dir))
                val proc = try { builder.start() } catch (e: Exception) {
                    i.error = "No se pudo iniciar Java integrado: ${e.message}"
                    i.log("[ERROR] ${i.error}")
                    throw IllegalStateException(i.error, e)
                }
                i.error = null
                i.process = proc
                i.log("[Synex] Iniciando ${s.getString("version")} con Java ${EmbeddedRuntime.majorFor(s.getString("version"))}...")
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(Intent(ctx, HostService::class.java))
                    else ctx.startService(Intent(ctx, HostService::class.java))
                }.onFailure { i.log("[AVISO] Android no permitió fijar el servicio: ${it.message}") }
                thread(name = "synex-console-$id", isDaemon = true) {
                    try { proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { i.log(it) }
                    } } catch (e: Exception) { i.log("[ERROR] Consola: ${e.message}") }
                    val exit = proc.waitFor()
                    i.log("[Synex] Servidor detenido (código $exit)")
                    if (exit != 0) i.error = "Java/Paper terminó con código $exit. Revisa la consola."
                    if (instances.values.none { it.process?.isAlive == true }) {
                        ctx.stopService(Intent(ctx, HostService::class.java))
                    }
                }
            }
            "stop" -> sendCommand(id, "stop")
            else -> error("Acción desconocida")
        }
    }

    private fun downloadPaper(i: Instance, version: String) {
        i.step("Consultando la versión $version")
        val builds = urlJson("https://fill.papermc.io/v3/projects/paper/versions/$version/builds") as JSONArray
        var best: JSONObject? = null
        for (j in 0 until builds.length()) {
            val item = builds.getJSONObject(j)
            if (item.optString("channel") != "STABLE") continue
            if (item.optJSONObject("downloads")?.optJSONObject("server:default")?.optString("url").isNullOrBlank()) continue
            if (best == null || item.getInt("id") > best.getInt("id")) best = item
        }
        val build = best ?: error("No existe una build STABLE de Paper $version")
        val meta = build.getJSONObject("downloads").getJSONObject("server:default")
        val url = meta.getString("url")
        val downloadUri = java.net.URI(url)
        require(downloadUri.scheme == "https" && downloadUri.host == "fill-data.papermc.io" && downloadUri.port == -1) {
            "URL de Paper no confiable"
        }
        val expected = meta.optJSONObject("checksums")?.optString("sha256") ?: ""
        require(expected.matches(Regex("[0-9a-fA-F]{64}"))) { "Paper no publicó SHA256 válido" }
        i.log("[Synex] Descargando Paper $version (build #${build.getInt("id")})...")
        i.step("Descargando Paper $version", 0)
        val dest = File(i.dir, "paper.jar")
        val part = File(i.dir, "paper.jar.download")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.connectTimeout = 25000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = false
            if (conn.responseCode !in 200..299) error("Descarga Paper HTTP ${conn.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val total = conn.contentLengthLong
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(bytes)
                        if (read < 0) break
                        count += read
                        if (count > 150L * 1024L * 1024L) error("Paper supera tamaño permitido")
                        digest.update(bytes, 0, read)
                        out.write(bytes, 0, read)
                        if (total > 0) {
                            val percent = (count * 100L / total).toInt().coerceIn(0, 99)
                            if (percent >= lastPercent + 4) {
                                i.step("Descargando Paper $version", percent)
                                lastPercent = percent
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            i.step("Verificando descarga SHA-256", 99)
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            require(count > 100_000L && got.equals(expected, ignoreCase = true)) { "Verificación SHA256 de Paper falló" }
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
            File(i.dir, "plugins").mkdirs()
            i.log("[Synex] Paper instalado. Acepta EULA y pulsa Iniciar.")
        } finally { part.delete() }
    }

    fun acceptEula(id: String) {
        val i = instance(id)
        File(i.dir, "eula.txt").writeText("# Aceptada expresamente en Synex Host\neula=true\n")
        i.log("[Synex] EULA aceptada")
    }
    fun sendCommand(id: String, command: String) {
        val i = instance(id)
        val cmd = command.trim().removePrefix("/")
        require(cmd.length in 1..512 && '\n' !in cmd && '\r' !in cmd) { "Comando inválido" }
        val proc = i.process ?: error("El servidor está apagado")
        check(proc.isAlive) { "El servidor está apagado" }
        proc.outputStream.write((cmd + "\n").toByteArray())
        proc.outputStream.flush()
        i.log("> $cmd")
    }
    /** Offline, user-controlled diagnostic text; never transmitted to any server. */
    fun diagnostics(id: String): String {
        val i = instance(id)
        val config = spec(id)
        val version = config.getString("version")
        val major = EmbeddedRuntime.majorFor(version)
        val runtime = File(ctx.filesDir, "runtimes/jre$major")
        val jar = File(i.dir, "paper.jar")
        return buildString {
            appendLine("SYNEX HOST v0.6 / DIAGNÓSTICO LOCAL")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Arquitecturas: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("Servidor: ${config.getString("name")} / Paper $version")
            appendLine("RAM: ${config.getInt("ramMb")} MB")
            appendLine("Java esperado: $major")
            appendLine("JRE modules: ${File(runtime, "lib/modules").isFile}")
            appendLine("JRE libjli: ${File(runtime, "lib/libjli.so").isFile}")
            appendLine("Java bin: ${File(runtime, "bin/java").isFile}")
            appendLine("Java ejecutable: ${File(runtime, "bin/java").canExecute()}")
            appendLine("Paper JAR: ${jar.isFile} (${jar.length()} bytes)")
            appendLine("Espacio disponible: ${ctx.filesDir.usableSpace / (1024 * 1024)} MB")
            appendLine("Etapa: ${i.installStep}")
            appendLine("Error: ${i.error ?: "Ninguno"}")
            appendLine("--- Últimos registros ---")
            i.logs(0).lines.takeLast(65).forEach { appendLine(it) }
        }.take(18000)
    }

    fun logs(id: String, since: Int) = instance(id).logs(since)

    private fun path(i: Instance, relative: String): File {
        require('\u0000' !in relative && '\\' !in relative && !relative.startsWith('/')) { "Ruta no válida" }
        val root = i.dir.canonicalPath
        val candidate = File(i.dir, relative).canonicalFile
        require(candidate.path == root || candidate.path.startsWith("$root/")) { "Ruta fuera del servidor" }
        return candidate
    }
    fun files(id: String, folder: String): List<FileEntry> {
        val i = instance(id)
        val dir = path(i, folder)
        require(dir.isDirectory) { "Carpeta inexistente" }
        return dir.listFiles().orEmpty().filterNot { java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).take(500)
            .map { FileEntry(it.name, it.relativeTo(i.dir).invariantSeparatorsPath, it.isDirectory, if (it.isFile) it.length() else 0L) }
    }
    fun fileContent(id: String, rel: String): String {
        val f = path(instance(id), rel)
        require(f.isFile && f.length() <= 1_048_576L && f.extension.lowercase() in setOf("txt", "properties", "yml", "yaml", "json", "toml", "cfg", "conf", "log", "md")) { "Solo archivos de texto hasta 1 MB" }
        return f.readText()
    }
    fun saveFile(id: String, rel: String, text: String) {
        val f = path(instance(id), rel)
        require(f.isFile && f.extension.lowercase() in setOf("txt", "properties", "yml", "yaml", "json", "toml", "cfg", "conf", "log", "md")) { "Archivo no editable" }
        require(text.toByteArray().size <= 1_048_576) { "Máximo 1 MB" }
        val temp = File(f.parentFile, f.name + ".synex-tmp")
        temp.writeText(text)
        require(temp.renameTo(f)) { "Error guardando el archivo" }
    }
    fun upload(id: String, rel: String, data: ByteArray) {
        val f = path(instance(id), rel)
        require(!f.exists() && f.parentFile?.isDirectory == true) { "El archivo ya existe o la carpeta no existe" }
        require(f.extension.lowercase() in setOf("jar", "zip", "yml", "yaml", "properties", "json", "txt", "toml", "cfg")) { "Tipo de archivo no admitido" }
        require(data.size <= 64 * 1024 * 1024) { "Archivo demasiado grande" }
        f.writeBytes(data)
    }
}
