package com.synex.host

import android.app.ActivityManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private val Bg = Color(0xFF0C1015)
private val Surface = Color(0xFF191E27)
private val Border = Color(0xFF303744)
private val Accent = Color(0xFF93A5FF)
private val Subtle = Color(0xFFB5BBCB)
private val Success = Color(0xFF62D4AA)
private val Warning = Color(0xFFF6C177)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 16, 22)
        window.navigationBarColor = android.graphics.Color.rgb(13, 16, 22)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Bg, surface = Surface,
                    onBackground = Color.White, onSurface = Color.White,
                    secondary = Subtle, outline = Border
                )
            ) { SynexScreen() }
        }
    }
}

@Composable
private fun SynexScreen() {
    val ctx = LocalContext.current
    // Server engine is embedded: no external setup, Termux or token.
    val token = ""
    val connected = true
    var connectionError by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf<List<ServerInfo>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var eulaDialog by remember { mutableStateOf(false) }
    var consoleCommand by remember { mutableStateOf("") }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var folder by remember { mutableStateOf("") }
    var fileEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var filesRevision by remember { mutableIntStateOf(0) }
    var filesError by remember { mutableStateOf("") }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var editorValue by remember { mutableStateOf("") }
    var newDialog by remember { mutableStateOf(false) }
    var newIsFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var ramDialog by remember { mutableStateOf(false) }
    var updatedRam by remember { mutableIntStateOf(2048) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val api = remember { ApiClient(ctx) }
    val selected = servers.firstOrNull { it.id == selectedId }

    fun execute(action: suspend () -> Unit) {
        scope.launch {
            if (busy) return@launch
            busy = true
            try {
                action()
                refresh += 1
            } catch (ex: Exception) {
                snackbar.showSnackbar(ex.message ?: "Error desconocido")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(connected, token, refresh) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            try {
                val result = api.servers()
                servers = result
                if (result.isNotEmpty() && result.none { it.id == selectedId }) selectedId = result.first().id
                connectionError = ""
            } catch (ex: Exception) {
                connectionError = ex.message ?: "Sin conexión"
            }
            delay(2200)
        }
    }

    LaunchedEffect(connected, selectedId, tab) {
        if (!connected || selectedId.isBlank() || tab != 1) return@LaunchedEffect
        logLines = emptyList()
        var cursor = 0
        while (isActive) {
            try {
                val page = api.logs(selectedId, cursor)
                if (page.lines.isNotEmpty()) logLines = (logLines + page.lines).takeLast(300)
                cursor = page.next
            } catch (_: Exception) { }
            delay(950)
        }
    }

    LaunchedEffect(connected, selectedId) {
        folder = ""
        fileEntries = emptyList()
        editorPath = null
    }

    LaunchedEffect(connected, selectedId, tab, folder, filesRevision) {
        if (!connected || selectedId.isBlank() || tab != 2) return@LaunchedEffect
        try {
            fileEntries = api.files(selectedId, folder)
            filesError = ""
        } catch (ex: Exception) {
            filesError = ex.message ?: "Error al abrir carpeta"
            fileEntries = emptyList()
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && selectedId.isNotBlank()) {
            execute {
                val fileName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: (uri.lastPathSegment ?: "upload.jar")
                val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "Nombre de archivo inválido" }
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= 64 * 1024 * 1024) { "Archivo demasiado grande (máx. 64 MB)" }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                } ?: error("No se pudo leer el archivo")
                val destination = if (folder.isBlank()) safeName else "$folder/$safeName"
                api.upload(selectedId, destination, bytes)
                filesRevision += 1
                snackbar.showSnackbar("Archivo subido: $safeName")
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null && selectedId.isNotBlank()) execute {
            val out = ctx.contentResolver.openOutputStream(uri)
                ?: error("No se pudo crear el respaldo")
            api.exportBackup(selectedId, out)
            snackbar.showSnackbar("Respaldo ZIP guardado")
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("SYNEX / LOCAL HOST", color = Accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mis servidores", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Smartphone, contentDescription = null, tint = Subtle, modifier = Modifier.size(18.dp))
                }
            }
        },
        bottomBar = {
            if (connected) NavigationBar(containerColor = Surface) {
                val labels = listOf("Inicio", "Consola", "Archivos", "Ajustes")
                val icons = listOf(Icons.Default.Dns, Icons.Default.Terminal, Icons.Default.Folder, Icons.Default.Settings)
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = null) }, label = { Text(label) })
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            if (true) {
                if (connectionError.isNotEmpty()) {
                    Text("Sin conexión: $connectionError", color = Warning, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                if (tab == 0) {
                    ServerPicker(servers, selectedId, onSelect = { selectedId = it }, onAdd = { createDialog = true })
                    Spacer(Modifier.height(12.dp))
                    if (selected == null) {
                        Text("Buscando servidores...", color = Subtle)
                    } else Dashboard(selected, busy,
                        onInstall = { execute { api.action(selected.id, "install") } },
                        onStart = { execute { api.action(selected.id, "start") } },
                        onStop = { execute { api.action(selected.id, "stop") } },
                        onEula = { eulaDialog = true },
                        onConsole = { tab = 1 },
                        onFiles = { tab = 2 },
                        onCopyDiagnostics = {
                            execute {
                                val report = api.diagnostics(selected.id)
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Synex Host", report))
                                snackbar.showSnackbar("Informe copiado. Pégalo en el chat para revisar el error.")
                            }
                        },
                        onCopyAddress = {
                            val ip = selected.lanAddress
                            if (ip != null) {
                                val address = "$ip:${selected.port}"
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Dirección LAN de Minecraft", address))
                                scope.launch { snackbar.showSnackbar("IP copiada: $address") }
                            }
                        },
                        onBackup = {
                            backupLauncher.launch("${selected.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}-backup.zip")
                        },
                        onEditRam = {
                            updatedRam = selected.ramMb
                            ramDialog = true
                        })
                } else if (tab == 1) {
                    ConsolePanel(selected, logLines, consoleCommand, onCommand = { consoleCommand = it },
                        onQuick = { command ->
                            selected?.let { current -> execute {
                                api.sendCommand(current.id, command)
                                snackbar.showSnackbar("Comando enviado: $command")
                            } }
                        },
                        onCopyLogs = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Consola Synex Host", logLines.joinToString("\n")))
                            scope.launch { snackbar.showSnackbar("Consola copiada") }
                        },
                        onSend = {
                            val cmd = consoleCommand.trim()
                            if (cmd.isNotBlank() && selected != null) execute {
                                api.sendCommand(selected.id, cmd)
                                consoleCommand = ""
                            }
                        })
                } else if (tab == 2) {
                    FilesPanel(selected, folder, fileEntries, filesError,
                        onBack = { folder = folder.substringBeforeLast('/', "") },
                        onOpen = { entry ->
                            if (entry.isDirectory) folder = entry.path
                            else execute {
                                editorValue = api.fileContent(selected!!.id, entry.path)
                                editorPath = entry.path
                            }
                        },
                        onRefresh = { filesRevision += 1 },
                        onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                        onNewFile = { newIsFolder = false; newName = ""; newDialog = true },
                        onNewFolder = { newIsFolder = true; newName = ""; newDialog = true },
                        onMenu = { actionEntry = it },
                        onBackup = {
                            selected?.let { server ->
                                backupLauncher.launch("${server.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}-backup.zip")
                            }
                        })
                } else {
                    SettingsPanel()
                }
            }
        }
    }

    if (createDialog) CreateDialog(
        api = api,
        dismiss = { createDialog = false },
        create = { name, version, ram -> execute {
            val obj = api.create(name, version, ram)
            selectedId = obj.getString("id")
            createDialog = false
            snackbar.showSnackbar("Servidor creado")
        } }
    )
    if (eulaDialog && selected != null) {
        AlertDialog(onDismissRequest = { eulaDialog = false },
            title = { Text("Aceptar EULA de Minecraft") },
            text = { Text("Confirma que leíste y aceptas la EULA de Minecraft (aka.ms/MinecraftEULA). Synex Host no la acepta automáticamente.") },
            confirmButton = {
                TextButton(onClick = {
                    eulaDialog = false
                    execute { api.acceptEula(selected.id) }
                }) { Text("Acepto la EULA") }
            }, dismissButton = { TextButton(onClick = { eulaDialog = false }) { Text("Cancelar") } })
    }
    if (ramDialog && selected != null) {
        AlertDialog(
            onDismissRequest = { ramDialog = false },
            title = { Text("RAM máxima · ${selected.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Límite de Java: ${updatedRam / 1024f} GB", fontWeight = FontWeight.Bold)
                    Slider(value = updatedRam.toFloat(), valueRange = 512f..16384f, steps = 61,
                        onValueChange = { updatedRam = ((it.toInt() / 256) * 256).coerceIn(512, 16384) })
                    Text("Para modificar la RAM el servidor debe estar apagado. No reserves toda la memoria del teléfono.",
                        color = Subtle, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                val amount = updatedRam
                val serverId = selected.id
                ramDialog = false
                execute {
                    api.updateRam(serverId, amount)
                    snackbar.showSnackbar("RAM configurada para el próximo inicio")
                }
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { ramDialog = false }) { Text("Cancelar") } }
        )
    }
    if (newDialog && selected != null) {
        AlertDialog(
            onDismissRequest = { newDialog = false },
            title = { Text(if (newIsFolder) "Crear carpeta" else "Crear archivo") },
            text = {
                OutlinedTextField(newName, onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (newIsFolder) "Nombre de carpeta" else "Ejemplo: config.yml") })
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val name = newName.trim()
                    val isFolder = newIsFolder
                    val currentFolder = folder
                    newDialog = false
                    execute {
                        require(name != "." && name != ".." && '/' !in name && '\\' !in name) {
                            "Nombre inválido"
                        }
                        api.createEntry(selectedId,
                            if (currentFolder.isBlank()) name else "$currentFolder/$name", isFolder)
                        filesRevision += 1
                        snackbar.showSnackbar(if (isFolder) "Carpeta creada" else "Archivo creado")
                    }
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancelar") } }
        )
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.name) },
            text = { Text("Administrar archivo o carpeta. El servidor debe estar apagado.") },
            confirmButton = { TextButton(onClick = {
                renameValue = entry.name
                renameTarget = entry
                actionEntry = null
            }) { Text("Renombrar") } },
            dismissButton = { TextButton(onClick = {
                deleteTarget = entry
                actionEntry = null
            }) { Text("Eliminar", color = Warning) } }
        )
    }

    renameTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar: ${entry.name}") },
            text = {
                OutlinedTextField(renameValue, onValueChange = { renameValue = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nuevo nombre") })
            },
            confirmButton = { TextButton(onClick = {
                val targetId = selectedId
                val name = renameValue.trim()
                renameTarget = null
                execute {
                    api.renameEntry(targetId, entry.path, name)
                    filesRevision += 1
                    snackbar.showSnackbar("Elemento renombrado")
                }
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } }
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar definitivamente") },
            text = { Text("¿Eliminar ${entry.path}? Se borrará también el contenido de la carpeta, sin posibilidad de deshacer.") },
            confirmButton = { TextButton(onClick = {
                val targetId = selectedId
                deleteTarget = null
                execute {
                    api.deleteEntry(targetId, entry.path)
                    filesRevision += 1
                    snackbar.showSnackbar("Elemento eliminado")
                }
            }) { Text("Eliminar", color = Warning) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
    if (editorPath != null && selected != null) {
        AlertDialog(
            onDismissRequest = { editorPath = null },
            title = { Text(editorPath!!, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OutlinedTextField(editorValue, onValueChange = { editorValue = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = Color.White),
                    modifier = Modifier.fillMaxWidth().height(330.dp), label = { Text("Contenido") })
            },
            confirmButton = { TextButton(onClick = {
                val path = editorPath!!
                execute {
                    api.saveFile(selected.id, path, editorValue)
                    editorPath = null
                    filesRevision += 1
                    snackbar.showSnackbar("Cambios guardados")
                }
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editorPath = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun ServerPicker(servers: List<ServerInfo>, chosen: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        servers.forEach { s ->
            FilterChip(selected = chosen == s.id, onClick = { onSelect(s.id) },
                label = { Text(s.name) }, modifier = Modifier.padding(end = 8.dp))
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.AddCircle, contentDescription = "Nuevo servidor", tint = Accent) }
    }
}

@Composable
private fun Dashboard(server: ServerInfo, busy: Boolean, onInstall: () -> Unit, onStart: () -> Unit,
                      onStop: () -> Unit, onEula: () -> Unit, onConsole: () -> Unit, onFiles: () -> Unit,
                      onCopyDiagnostics: () -> Unit, onCopyAddress: () -> Unit, onBackup: () -> Unit,
                      onEditRam: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, null, tint = Accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("${server.software} · ${server.version}", color = Subtle)
                }
                Surface(shape = RoundedCornerShape(50), color = Bg) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Circle, contentDescription = null,
                            tint = if (server.running) Success else if (server.installing) Warning else Subtle,
                            modifier = Modifier.size(7.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (server.ready) "EN LÍNEA" else if (server.running) "INICIANDO" else if (server.installing) "INSTALANDO" else "APAGADO",
                            color = if (server.running) Success else if (server.installing) Warning else Subtle,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            HorizontalDivider(color = Border)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("RAM MÁX.", "${server.ramMb / 1024f} GB", Modifier.weight(1f))
                Stat("PUERTO LAN", "${server.port}", Modifier.weight(1f))
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Bg) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DIRECCIÓN PARA MINECRAFT JAVA (RED LOCAL)", color = Subtle,
                            style = MaterialTheme.typography.labelSmall)
                        Text(server.lanAddress?.let { "$it:${server.port}" } ?: "Sin IP de Wi-Fi disponible",
                            color = Color.White, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onCopyAddress, enabled = server.lanAddress != null) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar IP", tint = Accent)
                    }
                }
            }
            Text("Solo red local. Fuera de la misma Wi-Fi se necesita túnel o configuración del router. El servidor debe estar completamente iniciado.",
                color = Subtle, style = MaterialTheme.typography.bodySmall)
            if (server.installing) {
                Text(server.installStep.ifBlank { "Preparando servidor..." }, color = Subtle,
                    style = MaterialTheme.typography.bodySmall)
                if (server.installProgress in 0..100)
                    LinearProgressIndicator(progress = { server.installProgress / 100f },
                        modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Border)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Border)
            }
            when {
                server.installing -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Instalación en curso...")
                }
                !server.installed -> Button(onClick = onInstall, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                    Text("Instalar Paper ${server.version}")
                }
                !server.eulaAccepted -> Button(onClick = onEula, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Leer y aceptar EULA")
                }
                server.running -> Button(onClick = onStop, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Detener de forma segura")
                }
                else -> Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Iniciar servidor")
                }
            }
            server.installError?.let { message ->
                Surface(color = Color(0xFF30231F), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Warning)
                            Spacer(Modifier.width(8.dp))
                            Text("No se pudo completar la operación", color = Warning,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(message, color = Color(0xFFFFD5B2),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onInstall,
                                enabled = !busy && !server.installing && !server.running) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Reintentar")
                            }
                            TextButton(onClick = onConsole) { Text("Ver registros") }
                        }
                        OutlinedButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copiar diagnóstico para soporte")
                        }
                    }
                }
            }
        }
        Panel {
            Text("Herramientas del servidor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onConsole, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Abrir consola")
            }
            OutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Explorar archivos y plugins")
            }
            OutlinedButton(onClick = onBackup, enabled = !server.running && !server.installing,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Archive, null); Spacer(Modifier.width(8.dp)); Text("Exportar respaldo ZIP")
            }
            OutlinedButton(onClick = onEditRam, enabled = !server.running && !server.installing,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(8.dp)); Text("Cambiar RAM del servidor")
            }
        }
        Text("La app ejecuta Java directamente en Android. El rendimiento depende de la memoria y temperatura del teléfono.",
            color = Subtle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier) {
    Column(modifier.background(Bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Subtle)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConsolePanel(server: ServerInfo?, lines: List<String>, command: String,
                         onCommand: (String) -> Unit, onSend: () -> Unit,
                         onQuick: (String) -> Unit, onCopyLogs: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Consola · ${server?.name ?: "..."}", modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onCopyLogs) { Icon(Icons.Default.ContentCopy, contentDescription = "Copiar consola", tint = Subtle) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("list", "save-all", "tps", "whitelist list", "time set day").forEach { command ->
                FilterChip(selected = false, enabled = server?.ready == true, onClick = { onQuick(command) },
                    label = { Text(command) })
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF090B10), modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer {
                Text(if (lines.isEmpty()) "[Synex] Aquí aparecerán los registros del servidor..."
                    else lines.joinToString("\n"),
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFD2D9E5)),
                    fontSize = MaterialTheme.typography.bodySmall.fontSize)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(command, onValueChange = onCommand, singleLine = true,
                modifier = Modifier.weight(1f), placeholder = { Text("say Hola mundo") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSend, enabled = server?.running == true && command.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Enviar comando", tint = Accent)
            }
        }
    }
}

@Composable
private fun FilesPanel(server: ServerInfo?, folder: String, files: List<FileEntry>, error: String,
                       onBack: () -> Unit, onOpen: (FileEntry) -> Unit, onRefresh: () -> Unit,
                       onUpload: () -> Unit, onNewFile: () -> Unit, onNewFolder: () -> Unit,
                       onMenu: (FileEntry) -> Unit, onBackup: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Archivos · ${server?.name ?: "..."}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = folder.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Carpeta anterior")
            }
            Text(if (folder.isEmpty()) "/" else "/$folder", modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Subtle)
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar") }
            IconButton(onClick = onUpload) { Icon(Icons.Default.UploadFile, contentDescription = "Subir archivo", tint = Accent) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onNewFolder, enabled = server?.running != true) {
                Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp)); Text("Carpeta")
            }
            OutlinedButton(onClick = onNewFile, enabled = server?.running != true) {
                Icon(Icons.Default.NoteAdd, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp)); Text("Archivo")
            }
            OutlinedButton(onClick = onBackup, enabled = server?.running != true) {
                Icon(Icons.Default.Archive, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp)); Text("Backup")
            }
        }
        if (error.isNotBlank()) Text(error, color = Warning)
        if (files.isEmpty()) Text("Carpeta vacía o cargando...", color = Subtle)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 15.dp)) {
            items(files, key = { it.path }) { file ->
                Surface(onClick = { onOpen(file) }, color = Surface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            null, tint = if (file.isDirectory) Accent else Subtle)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!file.isDirectory) Text("${file.size} bytes", color = Subtle, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onMenu(file) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Acciones de archivo", tint = Subtle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Panel {
            Text("Motor integrado", fontWeight = FontWeight.SemiBold)
            Text("Synex Host ejecuta Java y Minecraft dentro del teléfono, sin Termux, cuentas ni tokens.", color = Subtle)
            Text("El primer uso prepara Java integrado. La descarga de Paper necesita Internet.", style = MaterialTheme.typography.bodySmall)
            Text("Solo teléfonos Android ARM64. Los mundos están guardados en el espacio privado de la aplicación.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Synex Host v0.7 · LAN, backups, RAM y archivos · Sin Termux", color = Subtle,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CreateDialog(api: ApiClient, dismiss: () -> Unit, create: (String, String, Int) -> Unit) {
    val context = LocalContext.current
    val memory = remember {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }
    val suggestedMaxMb = (memory - 1536L).coerceIn(512L, 16384L)
    var name by remember { mutableStateOf("Nuevo servidor") }
    var version by remember { mutableStateOf("1.20.1") }
    var ram by remember { mutableIntStateOf(2048) }
    var versions by remember { mutableStateOf(listOf("1.20.1")) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var dropdown by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        try {
            versions = api.versions()
            loadError = ""
        } catch (e: Exception) {
            // Keep manual version input available when the catalog cannot be reached.
            loadError = "Sin catálogo: ${e.message ?: "Sin conexión"}"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Nuevo servidor Paper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { dropdown = true },
                        modifier = Modifier.fillMaxWidth(), enabled = versions.isNotEmpty()) {
                        Text("Minecraft: $version", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Cambiar versión")
                    }
                    DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false },
                        modifier = Modifier.heightIn(max = 330.dp)) {
                        versions.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = {
                                version = item
                                dropdown = false
                            })
                        }
                    }
                }
                if (loading) Text("Consultando versiones de PaperMC...", color = Subtle,
                    style = MaterialTheme.typography.bodySmall)
                if (loadError.isNotEmpty()) Text(loadError, color = Warning,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(version, onValueChange = { version = it.trim() },
                    label = { Text("Versión manual") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                HorizontalDivider(color = Border)
                Text("RAM máxima: ${ram / 1024f} GB", fontWeight = FontWeight.Bold)
                Slider(value = ram.toFloat(), onValueChange = { value ->
                    ram = ((value.toInt() / 256) * 256).coerceIn(512, 16384)
                }, valueRange = 512f..16384f, steps = 61)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024, 2048, 4096, 6144, 8192, 12288, 16384).forEach { mb ->
                        FilterChip(selected = ram == mb, onClick = { ram = mb },
                            label = { Text("${mb / 1024} GB") })
                    }
                }
                Text("RAM física del teléfono: aprox. ${memory / 1024f} GB. Reserva al menos 1.5 GB para Android y Java.",
                    style = MaterialTheme.typography.bodySmall, color = Subtle)
                if (ram > suggestedMaxMb) Text(
                    "Advertencia: asignar ${ram / 1024f} GB puede cerrar el servidor por falta de memoria.",
                    style = MaterialTheme.typography.bodySmall, color = Warning)
                Text("Rango permitido: 512 MB–16 GB. Se descarga Paper estable y la app usa Java 17 o 21 integrado según la versión.",
                    color = Subtle, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { create(name, version, ram) },
                enabled = name.isNotBlank() && Regex("""\d{1,2}\.\d{1,2}(\.\d{1,2})?""").matches(version)) {
                Text("Crear e instalar")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }
    )
}
