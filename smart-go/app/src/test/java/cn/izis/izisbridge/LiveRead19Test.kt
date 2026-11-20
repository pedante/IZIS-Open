package cn.izis.izisbridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in provider check: one two-visit analysis. Credentials never enter test output. */
class LiveRead19Test {
    @Test fun realWebSocketAnalysisAndBearerUsageWhenAvailable() {
        val filename = System.getenv("IZIS_READ19_TEST_FILE")
        assumeTrue("Live provider test is explicitly opt-in", filename != null)
        val privateConfig = JSONObject(File(filename!!).readText())
        val credential = privateConfig.optString("analysis_key").ifEmpty {
            privateConfig.getJSONObject("engine").getString("remote_url")
        }
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val usage = CountDownLatch(1)
        var result: JSONObject? = null
        lateinit var connection: Read19Connection
        connection = Read19Connection({
            if (connection.connected) connected.countDown()
            if (connection.lastUsage != null) usage.countDown()
        }, { _, response -> result = response; completed.countDown() })
        try {
            connection.configure(credential)
            connection.connect()
            assertTrue("Provider WebSocket handshake", connected.await(30, TimeUnit.SECONDS))
            connection.analyze(GoGame(9).query(2))
            assertTrue("Provider analysis completion", completed.await(90, TimeUnit.SECONDS))
            assertNotNull("Provider returned an analysis", result)
            assertTrue(result!!.getJSONObject("rootInfo").getInt("visits") > 0)
            if (credential.startsWith("kg_live_")) {
                connection.refreshUsage(true)
                assertTrue("Authenticated usage matches the published contract", usage.await(30, TimeUnit.SECONDS))
                assertTrue(usageText(connection.lastUsage!!).contains("Consumed:"))
            }
        } finally { connection.disconnect() }
    }
}
