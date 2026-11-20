package cn.izis.izisbridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    @Test fun candidateRankingUsesProviderOrder() {
        val moves = rankedMoves(org.json.JSONArray("""[{"move":"A1","order":2},{"move":"C3","order":0},{"move":"B2","order":1}]"""))
        assertEquals(listOf("C3", "B2", "A1"), (0 until moves.length()).map { moves.getJSONObject(it).getString("move") })
    }
    @Test fun captureSuicideUndoAndSgfRecovery() {
        val game = GoGame(9)
        for (p in listOf(1, 0, 9)) game.play(p)
        assertEquals(0, game.board[0]); assertEquals(3, game.moves.size)
        try { game.play(0); fail("suicide") } catch (_: IllegalArgumentException) {}
        game.play(null); game.play(null)
        assertTrue(game.twoPasses)
        game.result = "B+3.5"
        val restored = GoGame.restore(JSONObject(game.json().toString()))
        assertArrayEquals(game.board, restored.board); assertEquals(game.sgf(), restored.sgf())
        assertTrue(game.sgf().contains("RU[Chinese]HA[0]"))
        assertEquals("chinese-ogs", game.query().getString("rules"))
        assertTrue(game.sgf().contains("RE[B+3.5]"))
        game.undo(2); assertNull(game.result); assertFalse(game.twoPasses)
        assertEquals(3, game.moves.size)
    }
    @Test fun koCannotBeRecaptured() {
        val game = GoGame(9)
        // Black C2, B3, A2 surround White B2; White C1/A1 surround the capturing point B1.
        for (p in listOf(11, 10, 19, 2, 9, 0)) game.play(p)
        game.play(1)
        assertEquals(0, game.board[10])
        try { game.play(10); fail("ko") } catch (_: IllegalArgumentException) {}
    }
    @Test fun physicalCorrectionCannotPlayOrRepeatAiMove() {
        val game = GoGame(9)
        val sync = PhysicalSync()
        val board = IntArray(81)
        sync.update(game, board, 0); sync.update(game, board, 400); assertTrue(sync.ready)
        board[3] = 1
        assertFalse(sync.update(game, board, 500)); assertTrue(sync.update(game, board, 900))
        assertEquals(1, game.moves.size)
        game.play(4)
        sync.update(game, board, 1000); assertFalse(sync.ready)
        board[5] = 1
        sync.update(game, board, 1500); sync.update(game, board, 1900)
        assertEquals(2, game.moves.size)
        board[5] = 0; board[4] = 2
        sync.update(game, board, 2000); sync.update(game, board, 2400)
        assertTrue(sync.ready); assertEquals(2, game.moves.size)
        game.undo(); sync.update(game, board, 2500); assertFalse(sync.ready)
        assertFalse(sync.fresh(5000))
    }
    @Test fun fullGridAndReviewedLighting() {
        val board = BoardPosition.decode("~SDA" + "0".repeat(300) + "1" + "0".repeat(60) + "#")
        assertEquals(1, board[3 * 19 + 3])
        assertEquals(100, BoardPosition.fullPoint(0, 9))
        assertEquals(40, BoardPosition.boundary(9).count { it })
        assertEquals(56, BoardPosition.boundary(13).count { it })
        assertFalse(LedController.allowed("~SHP301,r000g032b000,1#"))
        assertTrue(LedController.allowed("~RGC#"))
        assertFalse(LedController.allowed("~SHP001,r000g032b000,1#"))
        try { BoardPosition.decode("~SDA012#"); fail("short snapshot") } catch (_: IllegalArgumentException) {}
    }
    @Test fun usageUsesOnlyContractFieldsAndRejectsInvalidCounters() {
        val value = JSONObject("""{"plan":{"allowance":123,"resetAt":"provider-date"},"used":23,"remaining":100,"credits":{"balance":7}}""")
        val text = usageText(value)
        assertTrue(text.contains("Consumed: 23")); assertTrue(text.contains("Remaining: 100"))
        assertTrue(text.contains("provider-date")); assertTrue(text.contains("123"))
        try { usageText(JSONObject("""{"plan":{},"used":"23","remaining":100}""")); fail("wrong type") } catch (_: IllegalArgumentException) {}
        try { usageText(JSONObject("""{"plan":{},"used":-1,"remaining":100}""")); fail("negative") } catch (_: IllegalArgumentException) {}
        assertEquals(ConnectionState.AUTH_ERROR, providerState(401))
        assertEquals(ConnectionState.EXHAUSTED, providerState(402))
        assertEquals(ConnectionState.BUSY, providerState(400, "CONCURRENCY_LIMIT_EXCEEDED"))
        for (bad in listOf("http://read19.com/key", "wss://evil.com/v1/katago/key", "kg_session_secret", "kg_live_key\nHeader: injected")) {
            try { validateCloudCredential(bad); fail("credential validation") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun teachingUsesBlackPerspectiveForBothPlayers() {
        assertEquals(4.0, scoreLoss(5.0, 1.0, 1)!!, 0.001)
        assertEquals(4.0, scoreLoss(1.0, 5.0, 2)!!, 0.001)
        assertEquals(0.0, scoreLoss(1.0, 5.0, 1)!!, 0.001)
        assertNull(scoreLoss(Double.NaN, 0.0, 1))
    }
}
