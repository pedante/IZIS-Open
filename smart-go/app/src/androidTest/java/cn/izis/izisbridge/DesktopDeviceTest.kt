package cn.izis.izisbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DesktopDeviceTest {
    @Test fun pinnedTlsControlsExistingKatrain() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "desktop-proof.json")
        assumeTrue("Requires the isolated virtual-board desktop proof", InstrumentationRegistry.getArguments().getString("desktopProof") == "true")
        assertTrue("Desktop proof fixture was not transferred", file.isFile)
        val config = JSONObject(file.readText())
        fun connection(pin: String = config.getString("fingerprint"), token: String = config.getString("token")) =
            DesktopConnection(config.getString("host"), config.getInt("port"), pin, token)
        fun envelope(op: String) = JSONObject().put("op", op).put("id", UUID.randomUUID().toString()).put("client", "api25-proof")
        val pin = config.getString("fingerprint")
        try {
            connection(pin = (if (pin[0] == '0') "1" else "0") + pin.drop(1)).request(envelope("status"))
            fail("Incorrect certificate pin was accepted")
        } catch (_: javax.net.ssl.SSLException) {}
        try {
            connection(token = "x".repeat(43)).request(envelope("status"))
            fail("Incorrect pairing key was accepted")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().startsWith("Authentication rejected")) }
        val client = connection()
        var state = client.request(envelope("acquire"))
        fun call(op: String, fields: JSONObject = JSONObject()): JSONObject {
            val request = envelope(op).put("revision", state.getInt("revision")).put("game_id", state.getString("game_id")).put("controller_epoch", state.getString("controller_epoch"))
            fields.keys().forEach { request.put(it, fields.get(it)) }
            val response = client.request(request)
            if (response.has("revision")) state = response
            return response
        }
        fun waitFor(predicate: (JSONObject) -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate(call("status"))) return
                Thread.sleep(500)
            }
            fail("Desktop did not reach the requested state")
        }
        try {
            assertEquals(1, state.getInt("protocol"))
            assertTrue(state.getBoolean("computer") && state.getBoolean("katrain") && state.getBoolean("engine"))
            assertEquals("touchscreen", state.getString("controller"))
            call("new", JSONObject().put("size", 9).put("color", "B").put("mode", "normal").put("difficulty", "ai:default"))
            var sequence = 0
            val stream = UUID.randomUUID().toString()
            fun observe(stones: org.json.JSONArray) {
                val full = IntArray(361)
                for (i in 0 until stones.length()) {
                    val stone = stones.getJSONArray(i)
                    full[(stone.getInt(1) + 5) * 19 + stone.getInt(0) + 5] = if (stone.getString(2) == "B") 1 else 2
                }
                repeat(2) {
                    call("observe", JSONObject().put("physical", org.json.JSONArray(full.toList()))
                        .put("stream", stream).put("sequence", ++sequence))
                    Thread.sleep(700)
                }
            }
            observe(org.json.JSONArray())
            waitFor { it.getBoolean("ready") }
            val stones = org.json.JSONArray("""[[0,1,"B"]]""")
            observe(stones)
            val full = IntArray(361).apply { this[6 * 19 + 5] = 1 }
            call("physical_proposal", JSONObject().put("point", org.json.JSONArray(listOf(0, 1)))
                .put("color", "B").put("physical", org.json.JSONArray(full.toList())))
            assertTrue(state.getJSONObject("proposal").getBoolean("accepted"))
            waitFor { it.getInt("depth") == 2 }
            observe(state.getJSONArray("stones"))
            waitFor { it.getBoolean("ready") }
            call("test_analysis")
            waitFor { it.optString("test_analysis") == "Successful analysis response" }
            assertEquals("ready", state.getString("analysis"))
            assertTrue(state.getDouble("last_analysis") > 0)
            call("resign")
            assertEquals("W+R", state.getString("result"))
            assertTrue(state.getJSONObject("record").getString("sgf").contains("RE[W+R]"))
            assertTrue(call("library").getJSONArray("games").length() > 0)
        } finally { call("release") }
        assertEquals("desktop", state.getString("controller"))
    }
}
