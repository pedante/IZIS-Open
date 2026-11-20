package cn.izis.izisbridge

import org.junit.Assert.*
import org.junit.Test

class PhysicalPositionTest {
    @Test fun confirmationStabilityAndReconnect() {
        val state = PhysicalPosition()
        val empty = IntArray(361)
        state.accept(empty, "game:1")
        state.observe(empty, 0); state.observe(empty, 600)
        assertTrue(state.ready(600))
        val placed = empty.copyOf().apply { this[BoardPosition.fullPoint(0, 9)] = 1 }
        state.observe(placed, 700)
        assertNull(state.proposal(1, 9, 1299))
        assertEquals(0, state.proposal(1, 9, 1300))
        assertNull(state.proposal(2, 9, 1300))
        assertArrayEquals(empty, state.accepted)
        state.submitted(); assertNull(state.proposal(1, 9, 1300))
        state.reset(); state.observe(placed, 2000)
        assertNull(state.proposal(1, 9, 2600))
    }
    @Test fun outsideStoneAndMultipleChangesCannotPropose() {
        val state = PhysicalPosition(); val empty = IntArray(361)
        state.accept(empty, "1"); state.observe(empty, 0); state.observe(empty, 600)
        val board = empty.copyOf().apply { this[0] = 1 }
        state.observe(board, 700); assertNull(state.proposal(1, 9, 1400))
        board[BoardPosition.fullPoint(0, 9)] = 1
        state.observe(board, 1500); assertNull(state.proposal(1, 9, 2200))
    }
    @Test fun displacedStonesRemainCorrectionsUntilRestored() {
        for (color in 1..2) {
            val state = PhysicalPosition()
            val accepted = IntArray(361).apply { this[60] = color }
            state.accept(accepted, "1"); state.observe(accepted, 0); state.observe(accepted, 600)
            val displaced = IntArray(361).apply { this[72] = color }
            state.observe(displaced, 700); state.observe(displaced, 1300)
            assertNull(state.proposal(color, 19, 1300))
            assertEquals(mapOf(60 to color), state.corrections(19))
            assertArrayEquals(accepted, state.accepted)
            state.observe(accepted, 1400)
            assertEquals(mapOf(60 to color), state.corrections(19))
            assertFalse(state.ready(1400))
            state.observe(accepted, 2000); assertTrue(state.ready(2000))
            assertTrue(state.corrections(19).isEmpty())
        }
    }
    @Test fun wrongColorAndCapturedStonesHaveScreenGuidance() {
        val state = PhysicalPosition()
        state.accept(IntArray(361).apply { this[60] = 1; this[72] = 2 }, "1")
        state.observe(IntArray(361).apply { this[60] = 2; this[72] = 2 }, 0)
        assertTrue(state.guidance(19).contains("Replace stone at D4 with black"))
        assertEquals(mapOf(60 to 1), state.corrections(19))
        state.accept(IntArray(361).apply { this[60] = 2 }, "capture:2")
        assertTrue(state.guidance(19).contains("Remove extra stone at Q4"))
        assertTrue(state.corrections(19).isEmpty())
        assertTrue(state.differs())
    }
}
