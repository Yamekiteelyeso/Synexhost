package com.synex.host

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Android arm64 OpenJDK distributions are included as signed/pinned APK assets at build time.
 * Keep the launcher at JRE/bin/java so the JVM can resolve its own home directory.
 */
internal object EmbeddedRuntime {
    fun majorFor(version: String): Int {
        val parts = version.split('.').mapNotNull { it.toIntOrNull() }
        require(parts.size >= 2 && parts[0] == 1) { "Versión Minecraft inválida" }
        if (parts[1] < 17) throw IllegalArgumentException("Esta versión de Minecraft necesita Java 8; todavía no está integrada")
        // Paper 1.20.5+ uses Java 21. Future Paper may need Java 25.
        return if (parts[1] > 20 || (parts[1] == 20 && (parts.getOrNull(2) ?: 0) >= 5)) 21 else 17
    }

    fun prepare(ctx: Context, version: String, log: (String) -> Unit): Pair<File, File> {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Synex Host autónomo requiere un teléfono ARM64" }
        val major = majorFor(version)
        val root = File(ctx.filesDir, "runtimes/jre$major")
        val java = File(root, "bin/java")
        if (!File(root, "lib/modules").isFile ||
            !File(root, "lib/libjli.so").isFile || !java.isFile) {
            log("[Synex] Preparando Java $major integrado (primera ejecución)...")
            val staging = File(ctx.cacheDir, "synex-jre-$major-staging")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                ctx.assets.open("runtimes/jre$major.tar.xz").use { stream ->
                    XZInputStream(stream.buffered(128 * 1024)).use { xz ->
                        TarArchiveInputStream(xz).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val name = entry.name.removePrefix("./")
                                if (name.isNotBlank() && name != ".") {
                                    val target = File(staging, name)
                                    val basePath = staging.canonicalPath + File.separator
                                    require(target.canonicalPath.startsWith(basePath)) { "Ruta insegura en el paquete Java" }
                                    when {
                                        entry.isDirectory -> target.mkdirs()
                                        entry.isSymbolicLink -> {
                                            val link = entry.linkName
                                            // OpenJDK includes legitimate links such as
                                            // legal/java.xml/LICENSE -> ../java.base/LICENSE.
                                            // Reject escapes, not every relative '..' component.
                                            val extractionRoot = staging.canonicalPath
                                            val resolved = File(target.parentFile, link).canonicalPath
                                            require(link.isNotBlank() && !link.startsWith('/') &&
                                                (resolved == extractionRoot || resolved.startsWith(extractionRoot + File.separator))) {
                                                "Enlace Java sale del directorio permitido"
                                            }
                                            target.parentFile?.mkdirs()
                                            if (Files.isSymbolicLink(target.toPath())) target.delete()
                                            Os.symlink(link, target.absolutePath)
                                        }
                                        entry.isFile -> {
                                            target.parentFile?.mkdirs()
                                            FileOutputStream(target).use { tar.copyTo(it) }
                                        }
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
                require(File(staging, "lib/modules").isFile && File(staging, "lib/libjli.so").isFile &&
                    File(staging, "bin/java").isFile) {
                    "Java $major incompleto en el APK"
                }
                root.parentFile?.mkdirs()
                root.deleteRecursively()
                if (!staging.renameTo(root)) {
                    staging.copyRecursively(root, overwrite = true)
                    staging.deleteRecursively()
                }
                log("[Synex] Java $major listo")
            } catch (error: Exception) {
                staging.deleteRecursively()
                throw error
            }
        }
        // Restore +x permissions lost when unpacking the tar stream.
        listOf(java, File(root, "lib/jspawnhelper")).filter { it.isFile }.forEach { executable ->
            try { Os.chmod(executable.absolutePath, 493) } // 0755
            catch (e: Exception) { throw IllegalStateException("No se pudo habilitar Java $major: ${e.message}", e) }
        }
        require(java.canExecute()) { "Java $major no tiene permiso de ejecución" }
        return root to java
    }

    /** Verify the JVM can start before downloading a Minecraft server. */
    fun selfTest(ctx: Context, runtime: File, java: File, serverDir: File, log: (String) -> Unit) {
        val process = try {
            ProcessBuilder(java.absolutePath, "-version")
                .directory(runtime)
                .redirectErrorStream(true)
                .also { it.environment().putAll(environment(ctx, runtime, serverDir)) }
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("No se puede iniciar Java en este Android: ${e.message}", e)
        }
        try {
            if (!process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Java no respondió durante 25 segundos")
            }
            val output = process.inputStream.bufferedReader().readText().take(1200).trim()
            if (output.isNotBlank()) log("[Java] $output")
            require(process.exitValue() == 0 && output.contains("version", ignoreCase = true)) {
                "Java no inició correctamente (código ${process.exitValue()}): ${output.take(300)}"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun environment(ctx: Context, runtime: File, serverDir: File): Map<String, String> {
        val libs = listOf(File(runtime, "lib"), File(runtime, "lib/server"), File(ctx.applicationInfo.nativeLibraryDir))
        return mapOf(
            "JAVA_HOME" to runtime.absolutePath,
            "LD_LIBRARY_PATH" to libs.joinToString(":") { it.absolutePath },
            "TMPDIR" to File(serverDir, "tmp").apply { mkdirs() }.absolutePath,
            "HOME" to ctx.filesDir.absolutePath,
            "LANG" to "en_US.UTF-8"
        )
    }
}
