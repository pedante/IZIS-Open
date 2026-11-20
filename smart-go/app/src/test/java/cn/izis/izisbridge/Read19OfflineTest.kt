package cn.izis.izisbridge

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class Read19OfflineTest {
    @Test fun existingRequestPathAndTimeoutPreserveGame() {
        val completed = mutableListOf<String>()
        val connection = Read19Connection({}, { id, _ -> completed.add(id) })
        var sent = ""
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://read19.com/v1/katago").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { sent = text; return true }
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        fun set(name: String, value: Any) = Read19Connection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
        set("socket", socket); set("connected", true)
        val game = GoGame(9); game.play(10)
        val history = game.sgf()
        val id = connection.analyze(game.query(8))
        val query = JSONObject(sent)
        assertEquals(9, query.getInt("boardXSize")); assertEquals(8, query.getInt("maxVisits"))
        assertEquals("B2", query.getJSONArray("moves").getJSONArray(0).getString(1))
        assertEquals("chinese-ogs", query.getString("rules"))
        @Suppress("UNCHECKED_CAST")
        val pending = Read19Connection::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(connection) as ConcurrentHashMap<String, Long>
        pending[id] = System.currentTimeMillis() - 90_001
        connection.checkTimeout()
        assertFalse(connection.connected); assertTrue(connection.status.message.contains("timed out"))
        assertEquals(listOf(id), completed); assertEquals(history, game.sgf())
    }
    @Test fun offlineCandidatesRemainRecommendationsUntilControllerPlays() {
        val game = GoGame(9)
        val moves = rankedMoves(JSONArray("""[{"move":"B2","order":1,"winrate":0.51,"prior":0.2},{"move":"C3","order":0,"winrate":0.56,"prior":0.3}]"""))
        assertTrue(game.moves.isEmpty())
        val recommendation = moves.getJSONObject(0)
        assertEquals(0.56, recommendation.getDouble("winrate"), 0.001)
        game.play(game.point(recommendation.getString("move")))
        assertEquals(1, game.board[20])
        assertArrayEquals(game.board, GoGame.restore(JSONObject(game.json().toString())).board)
    }
}
