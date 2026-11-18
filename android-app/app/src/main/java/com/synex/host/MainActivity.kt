package com.synex.host

import android.app.ActivityManager
import android.content.Context
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

private val Bg = Color(0xFF0D1016)
private val Surface = Color(0xFF191D27)
private val Border = Color(0xFF2C3241)
private val Accent = Color(0xFF9198FF)
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
    val prefs = remember { ctx.getSharedPreferences("synex-connection", 0) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var connected by remember { mutableStateOf(token.isNotBlank()) }
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
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val api = remember(token) { ApiClient(token.trim()) }
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

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("SYNEX", color = Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Host", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Text("LOCAL · ANDROID", color = Subtle, style = MaterialTheme.typography.labelSmall)
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
            if (!connected) {
                ConnectionPanel(token, onToken = { token = it }, onConnect = {
                    execute {
                        require(token.trim().isNotBlank()) { "Introduce el token mostrado en Termux" }
                        api.servers()
                        prefs.edit().putString("token", token.trim()).apply()
                        connected = true
                    }
                }, busy)
            } else {
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
                        onFiles = { tab = 2 })
                } else if (tab == 1) {
                    ConsolePanel(selected, logLines, consoleCommand, onCommand = { consoleCommand = it },
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
                        onUpload = { uploadLauncher.launch(arrayOf("*/*")) })
                } else {
                    SettingsPanel(connectedToken = token, onDisconnect = {
                        connected = false
                        servers = emptyList()
                        selectedId = ""
                    })
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
private fun ConnectionPanel(token: String, onToken: (String) -> Unit, onConnect: () -> Unit, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 20.dp)) {
        Text("Conecta el motor local", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("El proceso de Minecraft corre en Termux. Synex Host funciona como panel nativo para controlarlo desde Android.", color = Subtle)
        Panel {
            Text("PASO 1", color = Accent, fontWeight = FontWeight.Bold)
            Text("En Termux, instala las dependencias y ejecuta ./start_backend.sh desde la carpeta SynexHost.")
            HorizontalDivider(color = Border)
            Text("PASO 2", color = Accent, fontWeight = FontWeight.Bold)
            Text("Copia el TOKEN que aparece en Termux. La conexión siempre se hace a 127.0.0.1:8765.")
            OutlinedTextField(token, onValueChange = onToken, modifier = Modifier.fillMaxWidth(),
                label = { Text("Token de acceso") }, singleLine = true)
            Button(onClick = onConnect, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Conectar")
            }
        }
        Text("No requiere root. Termux debe permanecer activo para mantener el servidor y el panel disponibles.", color = Subtle,
            style = MaterialTheme.typography.bodySmall)
    }
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
                      onStop: () -> Unit, onEula: () -> Unit, onConsole: () -> Unit, onFiles: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, null, tint = Accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("${server.software} · ${server.version}", color = Subtle)
                }
                Text(if (server.running) "ONLINE" else if (server.installing) "INSTALANDO" else "OFFLINE",
                    color = if (server.running) Success else if (server.installing) Warning else Subtle,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Border)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("RAM MÁX.", "${server.ramMb / 1024f} GB", Modifier.weight(1f))
                Stat("PUERTO", "${server.port}*", Modifier.weight(1f))
            }
            Text("* Puerto inicial. Puede modificarse en server.properties.", color = Subtle, style = MaterialTheme.typography.labelSmall)
            when {
                !server.installed -> Button(onClick = onInstall, enabled = !busy && !server.installing,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                    Text(if (server.installing) "Descargando Paper..." else "Instalar Paper ${server.version}")
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
            server.installError?.let { Text("Error de instalación: $it", color = Warning) }
        }
        Panel {
            Text("Administración", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onConsole, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Abrir consola")
            }
            OutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Explorar archivos y plugins")
            }
        }
        Text("El consumo de CPU, TPS y jugadores en tiempo real se añadirá en una siguiente versión; no mostramos cifras simuladas.",
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
                         onCommand: (String) -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Consola · ${server?.name ?: "..."}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
                       onBack: () -> Unit, onOpen: (FileEntry) -> Unit, onRefresh: () -> Unit, onUpload: () -> Unit) {
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
                        Icon(Icons.Default.ChevronRight, null, tint = Subtle)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(connectedToken: String, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Panel {
            Text("Motor local", fontWeight = FontWeight.SemiBold)
            Text("http://127.0.0.1:8765", color = Subtle, fontFamily = FontFamily.Monospace)
            Text("Token configurado: ${if (connectedToken.isNotBlank()) "Sí" else "No"}", color = Subtle)
            Text("El token permanece en las preferencias privadas de la app. No lo compartas.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Cambiar token o reconectar") }
        Text("Synex Host v0.2 · Panel Android nativo / motor Termux", color = Subtle,
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
                Text("RAM física del teléfono: aprox. ${memory / 1024f} GB. Reserva al menos 1.5 GB para Android y Termux.",
                    style = MaterialTheme.typography.bodySmall, color = Subtle)
                if (ram > suggestedMaxMb) Text(
                    "Advertencia: asignar ${ram / 1024f} GB puede cerrar el servidor o incluso Termux por falta de memoria.",
                    style = MaterialTheme.typography.bodySmall, color = Warning)
                Text("Rango permitido: 512 MB–16 GB. Instalar requiere una build estable oficial; algunas versiones necesitan otra versión de Java.",
                    color = Subtle, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { create(name, version, ram) },
                enabled = name.isNotBlank() && Regex("""\d{1,2}\.\d{1,2}(\.\d{1,2})?""").matches(version)) {
                Text("Crear")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }
    )
}
