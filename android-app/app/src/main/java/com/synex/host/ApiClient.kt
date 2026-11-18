package com.synex.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The Android app now talks directly to its own engine; no HTTP, Termux or token. */
class ApiClient(context: Context) {
    private val engine = LocalEngine.get(context.applicationContext)

    suspend fun servers(): List<ServerInfo> = withContext(Dispatchers.IO) { engine.servers() }
    suspend fun versions(): List<String> = withContext(Dispatchers.IO) { engine.versions() }
    suspend fun create(name: String, version: String, ramMb: Int): JSONObject =
        withContext(Dispatchers.IO) { engine.create(name, version, ramMb) }
    suspend fun action(id: String, action: String) = withContext(Dispatchers.IO) { engine.action(id, action) }
    suspend fun acceptEula(id: String) = withContext(Dispatchers.IO) { engine.acceptEula(id) }
    suspend fun sendCommand(id: String, command: String) = withContext(Dispatchers.IO) { engine.sendCommand(id, command) }
    suspend fun logs(id: String, since: Int): LogsPage = withContext(Dispatchers.IO) { engine.logs(id, since) }
    suspend fun diagnostics(id: String): String = withContext(Dispatchers.IO) { engine.diagnostics(id) }
    suspend fun files(id: String, path: String): List<FileEntry> = withContext(Dispatchers.IO) { engine.files(id, path) }
    suspend fun fileContent(id: String, path: String): String = withContext(Dispatchers.IO) { engine.fileContent(id, path) }
    suspend fun saveFile(id: String, path: String, content: String) = withContext(Dispatchers.IO) { engine.saveFile(id, path, content) }
    suspend fun upload(id: String, path: String, bytes: ByteArray) = withContext(Dispatchers.IO) { engine.upload(id, path, bytes) }
    suspend fun createEntry(id: String, path: String, directory: Boolean) =
        withContext(Dispatchers.IO) { engine.createEntry(id, path, directory) }
    suspend fun renameEntry(id: String, path: String, newName: String) =
        withContext(Dispatchers.IO) { engine.renameEntry(id, path, newName) }
    suspend fun deleteEntry(id: String, path: String) =
        withContext(Dispatchers.IO) { engine.deleteEntry(id, path) }
    suspend fun exportBackup(id: String, output: java.io.OutputStream) =
        withContext(Dispatchers.IO) { engine.exportBackup(id, output) }
    suspend fun updateRam(id: String, ramMb: Int) =
        withContext(Dispatchers.IO) { engine.updateRam(id, ramMb) }
}

data class ServerInfo(
    val id: String, val name: String, val software: String, val version: String,
    val ramMb: Int, val port: Int, val installed: Boolean, val installing: Boolean,
    val running: Boolean, val eulaAccepted: Boolean, val installError: String?,
    val installStep: String = "", val installProgress: Int = -1, val lanAddress: String? = null,
    val ready: Boolean = false
)

data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)
data class LogsPage(val lines: List<String>, val next: Int)
