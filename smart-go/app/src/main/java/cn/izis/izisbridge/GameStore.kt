package cn.izis.izisbridge

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class GameStore(context: Context) {
    private val directory = File(context.filesDir, "games").apply { mkdirs() }
    @Synchronized fun save(record: JSONObject, profile: String) {
        val id = record.getString("id")
        require(id.matches(Regex("[0-9a-f]{32}")) && profile in listOf("cloud", "desktop"))
        val value = JSONObject(record.toString()).put("profile", profile).put("saved_at", System.currentTimeMillis())
        val file = AtomicFile(File(directory, "$profile-$id.json"))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun all(): List<JSONObject> = (directory.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { it.name.removeSuffix(".bak") }.distinct().mapNotNull { name ->
            try { JSONObject(String(AtomicFile(File(directory, name)).readFully(), Charsets.UTF_8)) } catch (_: Exception) { null }
        }.sortedByDescending { it.optLong("saved_at") }
}
