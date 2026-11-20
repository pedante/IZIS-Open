package cn.izis.izisbridge
import org.junit.Assert.*
import org.junit.Test
class ClockButtonsTest {
    @Test fun onlyWhiteLongPressAndNoDuplicate() {
        val b = ClockButtons()
        for (frame in listOf("~WKY#", "~BKY#", "~BTK#", "WTK", "~WTK#garbage")) assertFalse(b.accept(frame, 1000))
        assertTrue(b.accept("~WTK#", 1000))
        assertFalse(b.accept("~WTK#", 1200))
        assertTrue(b.accept("~WTK#", 2000))
    }
}
