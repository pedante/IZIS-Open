package cn.izis.izisbridge

import android.content.*
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ServiceLifecycleTest {
    @Test fun fullSnapshotsReviewedLightCleanupAndReconnect() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val endpoint = Intent().setComponent(ComponentName(instrumentation.context.packageName, MockSerialService::class.java.name))
        var binder: IBinder? = null
        val monitor = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = base.bindService(endpoint, connection, flags)
        }
        fun waitFor(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10000
            while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue(check())
        }
        fun stats(): List<Int> {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { binder!!.transact(100, data, reply, 0); reply.readException(); return List(3) { reply.readInt() } }
            finally { data.recycle(); reply.recycle() }
        }
        fun last(): String {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { binder!!.transact(103, data, reply, 0); reply.readException(); reply.readInt(); return reply.readString()!! }
            finally { data.recycle(); reply.recycle() }
        }
        fun lights(): List<Pair<Long, String>> {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                binder!!.transact(106, data, reply, 0); reply.readException()
                return reply.createStringArrayList()!!.map { it.split('\t', limit = 2) }
                    .filter { it[1] != "~STA#" }.map { it[0].toLong() to it[1] }
            } finally { data.recycle(); reply.recycle() }
        }
        fun send(position: IntArray) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeString("~SDA" + position.reversed().joinToString("") + "#")
                binder!!.transact(104, data, reply, 0); reply.readException()
            } finally { data.recycle(); reply.recycle() }
        }
        fun command(code: Int, value: Int) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { data.writeInt(value); binder!!.transact(code, data, reply, 0); reply.readException() }
            finally { data.recycle(); reply.recycle() }
        }
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        var board: IntArray? = null
        var occupiedAt = 0L
        lateinit var hardware: BoardHardware
        instrumentation.runOnMainSync { hardware = BoardHardware(context, { messages.add(it) }, {
            board = it
            if (it?.get(60) != 0 && occupiedAt == 0L) occupiedAt = SystemClock.elapsedRealtime()
        }) }
        assertTrue(base.bindService(endpoint, monitor, Context.BIND_AUTO_CREATE))
        try {
            waitFor { binder != null }
            assertEquals(0, stats()[0])
            instrumentation.runOnMainSync { hardware.start(19) }
            waitFor { board?.size == 361 }
            instrumentation.runOnMainSync { hardware.display(mapOf(60 to 1), 19) }
            waitFor { lights().lastOrNull()?.second == "~SHP301,${LedController.GREEN},1#" }
            occupiedAt = 0
            send(IntArray(361).apply { this[60] = 1 })
            waitFor { lights().lastOrNull()?.second == "~SHP301,${LedController.OFF},1#" }
            assertTrue("Targeted off must precede 600-ms move stabilization", lights().last().first - occupiedAt in 0..599)
            val count = lights().size
            instrumentation.runOnMainSync { hardware.display(mapOf(60 to 2), 19) }
            Thread.sleep(1300)
            assertEquals("Wrong-colored occupied point stays dark", count, lights().size)
            send(IntArray(361))
            waitFor { lights().lastOrNull()?.second == "~SHP301,${LedController.WHITE},1#" }
            instrumentation.runOnMainSync { hardware.stop() }
            waitFor { stats()[1] == 1 }
            val traceFile = java.io.File(base.filesDir, "lighting-diagnostics.txt")
            waitFor { traceFile.readText().contains("service Disconnected — local binding released") }
            val trace = traceFile.readText().substringAfterLast("cleanup requested")
            assertTrue(trace.indexOf("dispatch ~RGC#") in 0 until trace.indexOf("ack=~ALC#"))
            assertTrue(trace.indexOf("ack=~ALC#") < trace.indexOf("unbind requested clean=true"))
            assertEquals("~RGC#", last())
            assertEquals(0, stats()[2])
            instrumentation.runOnMainSync { hardware.start(19) }
            waitFor { board?.size == 361 && stats()[0] == 2 }
            command(105, 0)
            instrumentation.runOnMainSync { hardware.display(mapOf(60 to 2), 19) }
            waitFor { lights().lastOrNull()?.second == "~SHP301,${LedController.WHITE},1#" }
            instrumentation.runOnMainSync { hardware.stop() }
            waitFor { stats()[1] == 2 }
            assertTrue(messages.any { it.contains("cleanup unconfirmed") })
            val timeout = traceFile.readText().substringAfterLast("cleanup requested")
            assertEquals(2, timeout.lines().count { it.endsWith("dispatch ~RGC#") })
            assertTrue(timeout.contains("unbind requested clean=false"))
            assertEquals(0, stats()[2])
            command(105, 1)
            instrumentation.runOnMainSync { hardware.start(19) }
            waitFor { board?.size == 361 && stats()[0] == 3 }
            waitFor { lights().lastOrNull()?.second == "~RGC#" }
            command(101, 6)
            waitFor { !hardware.active && board == null && stats()[1] == 3 }
            instrumentation.runOnMainSync { hardware.start(19) }
            waitFor { board?.size == 361 && stats()[0] == 4 }
            instrumentation.runOnMainSync { hardware.stop() }
            waitFor { stats()[1] == 4 }
            assertEquals(0, stats()[2])
        } finally {
            instrumentation.runOnMainSync { hardware.stop() }
            base.unbindService(monitor)
        }
    }
}
