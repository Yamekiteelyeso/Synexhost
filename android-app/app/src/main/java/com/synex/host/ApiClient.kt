package com.synex.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// The API never opens a LAN/internet address: only the local Termux backend.
class ApiClient(private val token: String) {
    private val base = "http://127.0.0.1:8765"

    private suspend fun call(
        method: String, path: String, data: ByteArray? = null,
        contentType: String = "application/json; charset=UTF-8"
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(base + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.useCaches = false
            if (data != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val json = JSONObject(text)
            if (status !in 200..299) throw IOException(json.optString("error", "HTTP $status"))
            json
        } catch (e: java.net.ConnectException) {
            throw IOException("No se detectó el motor local. Abre Termux y ejecuta ./start_backend.sh", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun toBody(obj: JSONObject) = obj.toString().toByteArray(Charsets.UTF_8)
    private fun enc(str: String) = URLEncoder.encode(str, "UTF-8")
    private fun serverPath(id: String) = "/api/servers/${enc(id)}"

    suspend fun servers(): List<ServerInfo> {
        val array = call("GET", "/api/servers").getJSONArray("servers")
        return (0 until array.length()).map { ServerInfo.from(array.getJSONObject(it)) }
    }

    suspend fun versions(): List<String> {
        val array = call("GET", "/api/versions").getJSONArray("versions")
        return (0 until array.length()).map { array.getString(it) }
    }

    suspend fun create(name: String, version: String, ramMb: Int) =
        call("POST", "/api/servers", toBody(JSONObject().put("name", name)
            .put("version", version).put("ramMb", ramMb)))

    suspend fun action(id: String, action: String) =
        call("POST", "${serverPath(id)}/$action", toBody(JSONObject()))

    suspend fun acceptEula(id: String) = call("POST", "${serverPath(id)}/eula",
        toBody(JSONObject().put("accepted", true)))

    suspend fun sendCommand(id: String, command: String) = call("POST", "${serverPath(id)}/command",
        toBody(JSONObject().put("command", command)))

    suspend fun logs(id: String, since: Int): LogsPage {
        val response = call("GET", "${serverPath(id)}/logs?since=$since")
        val arr = response.getJSONArray("lines")
        return LogsPage((0 until arr.length()).map { arr.getJSONObject(it).getString("text") },
            response.getInt("next"))
    }

    suspend fun files(id: String, path: String): List<FileEntry> {
        val arr: JSONArray = call("GET", "${serverPath(id)}/files?path=${enc(path)}")
            .getJSONArray("entries")
        return (0 until arr.length()).map { item ->
            val obj = arr.getJSONObject(item)
            FileEntry(obj.getString("name"), obj.getString("path"),
                obj.getBoolean("directory"), obj.getLong("size"))
        }
    }

    suspend fun fileContent(id: String, path: String): String =
        call("GET", "${serverPath(id)}/files/content?path=${enc(path)}").getString("content")

    suspend fun saveFile(id: String, path: String, content: String) =
        call("PUT", "${serverPath(id)}/files/content?path=${enc(path)}",
            toBody(JSONObject().put("content", content)))

    suspend fun upload(id: String, path: String, bytes: ByteArray) =
        call("POST", "${serverPath(id)}/files/upload?path=${enc(path)}", bytes,
            "application/octet-stream")
}

data class ServerInfo(
    val id: String, val name: String, val software: String, val version: String,
    val ramMb: Int, val port: Int, val installed: Boolean, val installing: Boolean,
    val running: Boolean, val eulaAccepted: Boolean, val installError: String?
) {
    companion object {
        fun from(o: JSONObject): ServerInfo = ServerInfo(
            o.getString("id"), o.getString("name"), o.getString("software"),
            o.getString("version"), o.getInt("ramMb"), o.optInt("port", 25565), o.getBoolean("installed"),
            o.getBoolean("installing"), o.getBoolean("running"),
            o.getBoolean("eulaAccepted"), o.optString("installError").takeUnless { it.isBlank() || it == "null" }
        )
    }
}

data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)
data class LogsPage(val lines: List<String>, val next: Int)
