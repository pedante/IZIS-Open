package cn.izis.izisbridge

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Test

class SuggestionLightHardwareTest {
    @Test fun targetedOffAfterBatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("physicalLights") == "true")
        check(Build.MODEL == "Uniwin M339")
        val replies = LinkedBlockingQueue<String>()
        lateinit var client: BoardServiceClient
        instrumentation.runOnMainSync {
            client = BoardServiceClient(instrumentation.targetContext, object : BoardServiceClient.Observer {
                override fun status(text: String, busy: Boolean) = Unit
                override fun message(text: String) {
                    val frame = text.removePrefix("RX: ")
                    if (frame in listOf("~ALC#", "~HSM#", "~HCS#") || frame.startsWith("~SDA")) replies.offer(frame)
                }
            })
            client.connect()
        }
        fun stage(message: String) = instrumentation.sendStatus(2, Bundle().apply { putString("lighting", message) })
        fun command(cmd: String, expected: String): String {
            replies.clear()
            instrumentation.runOnMainSync { client.send(cmd) }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (true) {
                val frame = replies.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: error("No $expected reply for $cmd")
                if (frame.startsWith(expected)) {
                    stage("$expected received for $cmd")
                    return frame
                }
            }
        }
        var lit = false
        try {
            var ready = false
            val bindDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!ready && android.os.SystemClock.elapsedRealtime() < bindDeadline) {
                instrumentation.runOnMainSync { ready = client.ready() }
                if (!ready) Thread.sleep(100)
            }
            check(ready) { "Manufacturer service not ready" }
            val board = BoardPosition.decode(command("~STA#", "~SDA"))
            val boundary = BoardPosition.boundary(9)
            val frame = boundary.indices.filter { boundary[it] && board[it] == 0 }
                .take(4).associate { 361 - it to LedController.GREEN }
            val hints = (36..44).map { BoardPosition.fullPoint(it, 9) }.filter { board[it] == 0 }
                .take(2).associate { 361 - it to LedController.RED }
            check(frame.size == 4 && hints.size == 2) { "The test needs four vacant frame points and two vacant center-row points" }
            command("~RGC#", "~ALC#")
            lit = true
            command(LedController.batch(frame), "~HSM#")
            command(LedController.batch(hints), "~HSM#")
            stage("Observe four green and two red lights for 20 seconds")
            Thread.sleep(20_000)
            for (point in hints.keys) {
                command("~SHP%03d,%s,1#".format(java.util.Locale.ROOT, point, LedController.OFF), "~HCS#")
                stage("Observe whether one red light went out and the four greens stayed lit")
                Thread.sleep(20_000)
            }
        } finally {
            try { if (lit) command("~RGC#", "~ALC#") }
            finally { instrumentation.runOnMainSync { client.destroy() } }
        }
    }
}
