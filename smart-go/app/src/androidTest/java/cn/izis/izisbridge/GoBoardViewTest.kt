package cn.izis.izisbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GoBoardViewTest {
    @Test fun missingSnapshotKeepsAcceptedStonesSolid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = GoBoardView(instrumentation.targetContext)
            view.size = 9
            view.expected = IntArray(81).apply { this[40] = 1 }
            view.layout(0, 0, 200, 200)
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            view.draw(canvas)
            assertEquals(Color.BLACK, bitmap.getPixel(104, 104))

            view.physical = IntArray(81)
            view.draw(canvas)
            assertNotEquals(Color.BLACK, bitmap.getPixel(104, 104))
        }
    }
}
