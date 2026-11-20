package cn.izis.izisbridge

import org.junit.Assert.*
import org.junit.Test

class LedControllerTest {
    @Test fun movesAndSuggestionRemovalLeaveFrameAlone() {
        for (size in listOf(9, 13, 19)) {
            val frame = BoardPosition.boundary(size).withIndex().filter { it.value }
                .associate { 361 - it.index to LedController.GREEN }
            val sent = mutableListOf<String>()
            val leds = LedController({ assertTrue(LedController.allowed(it)); sent.add(it) }, {})
            val board = IntArray(361)
            fun expect(command: String, ack: String) {
                sent.clear()
                leds.tick(1, board)
                leds.tick(2, board)
                assertEquals(listOf(command), sent)
                leds.receive(ack)
            }
            fun frameBatches() = frame.entries.chunked(4).forEach {
                expect(LedController.batch(it.associate { entry -> entry.toPair() }), "~HSM#")
            }
            leds.display(frame)
            frameBatches()
            for (point in 180..182) {
                leds.display(frame, point to LedController.WHITE)
                expect("~SHP${point},${LedController.WHITE},1#", "~HCS#")
                board[361 - point] = 1
                expect("~SHP${point},${LedController.OFF},1#", "~HCS#")
                sent.clear()
                leds.tick(3, board)
                assertTrue(sent.isEmpty())
            }
            val move = 183 to LedController.GREEN
            leds.display(frame, move)
            expect("~SHP183,${LedController.GREEN},1#", "~HCS#")
            val hints = mapOf(184 to LedController.RED, 185 to LedController.RED)
            leds.display(frame + hints, move)
            expect(LedController.batch(hints), "~HSM#")
            board[361 - 184] = 1
            leds.display(frame, move)
            expect("~SHP184,${LedController.OFF},1#", "~HCS#")
            expect("~SHP185,${LedController.OFF},1#", "~HCS#")
            expect("~SHP183,${LedController.GREEN},1#", "~HCS#")
            sent.clear()
            leds.tick(3, board)
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun releaseRequiresAlcAndRetriesOnlyOnce() {
        val sent = mutableListOf<String>()
        val leds = LedController({ sent.add(it) }, {})
        leds.display(mapOf(181 to LedController.RED))
        leds.tick(0)
        leds.cleanup()
        leds.tick(1)
        leds.receive("~HSM#")
        assertFalse(leds.clean())
        leds.tick(3001)
        leds.tick(6001)
        leds.tick(9001)
        assertEquals(listOf("~RGC#", "~RGC#"), sent.drop(1))
        assertFalse(leds.clean())
        leds.cleanup()
        leds.tick(10000)
        leds.receive("~ALC#")
        assertTrue(leds.clean())
    }
}
