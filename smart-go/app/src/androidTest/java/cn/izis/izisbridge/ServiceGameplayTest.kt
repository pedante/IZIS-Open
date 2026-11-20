package cn.izis.izisbridge

import android.content.*
import android.os.*
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ServiceGameplayTest {
    @Test fun richServiceSensorConfirmationAndRealOpponent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("desktopProof") == "true")
        runGame(false)
    }
    @Test fun standaloneAnalysisFixtureUsesSameServiceAndSensors() { runGame(true) }
    @Test fun standaloneAutomaticMoves() { runGame(true, true) }
    @Test fun desktopAutomaticMoves() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("desktopProof") == "true")
        runGame(false, true)
    }
    private fun runGame(standalone: Boolean, automatic: Boolean = false) {
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val i = InstrumentationRegistry.getInstrumentation(); val context = i.targetContext
        val originalIds = GameStore(context).all().map { it.getString("id") }.toSet()
        var service: TrainingService? = null; var mock: IBinder? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { service = (b as TrainingService.LocalBinder).service }
            override fun onServiceDisconnected(n: ComponentName) { service = null }
        }
        val mockConnection = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { mock = b }
            override fun onServiceDisconnected(n: ComponentName) { mock = null }
        }
        fun waitFor(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 90000
            while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            assertTrue(check())
        }
        val endpoint = Intent().setComponent(ComponentName(i.context.packageName, MockSerialService::class.java.name))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            context.bindService(Intent(context, TrainingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            context.bindService(endpoint, mockConnection, Context.BIND_AUTO_CREATE)
            try {
                waitFor { service != null && mock != null }
                val s = service!!
                val wrapper = object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this
                    override fun bindService(intent: Intent, c: ServiceConnection, flags: Int) = context.bindService(endpoint, c, flags)
                }
                val physical = TrainingService::class.java.getDeclaredMethod("physical", IntArray::class.java).apply { isAccessible = true }
                i.runOnMainSync {
                    val hardware = BoardHardware(wrapper, {}, { board -> physical.invoke(s, board) }, { s.requestSuggestions() })
                    TrainingService::class.java.getDeclaredField("hardware").apply { isAccessible = true }.set(s, hardware)
                    s.setAutomaticMoves(automatic)
                    s.selectProfile(if (standalone) "cloud" else "desktop")
                    if (standalone) {
                        val cloud = s.cloud
                        cloud.status.configuration = "Configured"
                        val resultCallback = TrainingService::class.java.getDeclaredMethod("analysisFinished", String::class.java, JSONObject::class.java).apply { isAccessible = true }
                        val socket = object : okhttp3.WebSocket {
                            override fun request() = okhttp3.Request.Builder().url("https://read19.com/v1/katago").build()
                            override fun queueSize() = 0L
                            override fun send(bytes: okio.ByteString) = false
                            override fun close(code: Int, reason: String?) = true
                            override fun cancel() {}
                            override fun send(text: String): Boolean {
                                val query = JSONObject(text)
                                assertEquals(9, query.getInt("boardXSize"))
                                assertEquals("chinese-ogs", query.getString("rules"))
                                Handler(Looper.getMainLooper()).post {
                                    val pending = Read19Connection::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(cloud) as MutableMap<*, *>
                                    pending.clear()
                                    val response = JSONObject("""{"rootInfo":{"visits":8,"scoreLead":0,"winrate":0.5},"moveInfos":[{"move":"C3","order":0,"prior":0.3,"winrate":0.5}]}""")
                                    if (query.getJSONArray("moves").length() >= 2) response.getJSONArray("moveInfos").getJSONObject(0).put("move", "D4")
                                    for (move in listOf("F5", "G6", "H7", "J8")) {
                                        response.getJSONArray("moveInfos").put(JSONObject().put("move", move).put("order", response.getJSONArray("moveInfos").length()))
                                    }
                                    response.put("moveInfos", rankedMoves(response.getJSONArray("moveInfos")))
                                    resultCallback.invoke(s, query.getString("id"), response)
                                }
                                return true
                            }
                        }
                        Read19Connection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(cloud, socket)
                        Read19Connection::class.java.getDeclaredField("connected").apply { isAccessible = true }.set(cloud, true)
                    } else {
                        s.configureDesktop(JSONObject(File(context.filesDir, "desktop-proof.json").readText()), false)
                        s.connect()
                    }
                }
                if (!standalone) waitFor { s.desktopConnected }
                var started = false
                i.runOnMainSync {
                    val busy = TrainingService::class.java.getDeclaredField("desktopBusy").apply { isAccessible = true }
                    if (!standalone) busy.setBoolean(s, true)
                    s.startGame(9, if (automatic) "W" else "B", false, "ai:default") { ok -> assertTrue(s.notice, ok); started = true }
                    if (!standalone) {
                        assertFalse(started)
                        try { s.newGame(9, "W", false, "ai:default"); fail("Duplicate New Game") } catch (_: IllegalStateException) {}
                        Handler(Looper.getMainLooper()).postDelayed({ busy.setBoolean(s, false) }, 200)
                    }
                }
                waitFor { started }
                waitFor { if (standalone) s.game != null else s.desktop?.optInt("depth") in 0..1 && s.desktop?.getJSONArray("board_size")?.getInt(0) == 9 }
                i.runOnMainSync { if (!s.hardware.active) s.startBoard() }
                val board = IntArray(361)
                fun send(position: IntArray) {
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        data.writeString("~SDA" + position.reversed().joinToString("") + "#")
                        mock!!.transact(104, data, reply, 0); reply.readException()
                    } finally { data.recycle(); reply.recycle() }
                }
                if (automatic) {
                    waitFor { if (standalone) s.game?.moves?.size == 1 else s.desktop?.optInt("depth") == 1 }
                    if (standalone) s.game!!.board.forEachIndexed { p, c -> board[BoardPosition.fullPoint(p, 9)] = c }
                    else {
                        val stones = s.desktop!!.getJSONArray("stones")
                        for (n in 0 until stones.length()) { val stone = stones.getJSONArray(n); board[BoardPosition.fullPoint(stone.getInt(1) * 9 + stone.getInt(0), 9)] = 1 }
                    }
                    send(board)
                }
                waitFor { if (standalone) s.sync.ready else s.desktop?.optBoolean("ready") == true }
                assertEquals(if (automatic) 1 else 0, if (standalone) s.game!!.moves.size else s.desktop!!.getInt("depth"))
                val humanPoint = (0 until 81).first { board[BoardPosition.fullPoint(it, 9)] == 0 }
                board[BoardPosition.fullPoint(humanPoint, 9)] = if (automatic) 2 else 1
                if (standalone && !automatic) {
                    send(IntArray(361).apply { this[0] = 1 })
                    waitFor { s.position.observed?.get(0) == 1 }
                    onView(withText(org.hamcrest.Matchers.containsString("Remove stones outside the playing region: A1")))
                        .perform(scrollTo()).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    assertNull(s.position.proposal(1, 9, SystemClock.elapsedRealtime()))
                    send(IntArray(361))
                    waitFor { s.position.ready(SystemClock.elapsedRealtime()) }
                }
                send(board)
                if (!automatic) {
                waitFor { if (standalone) s.position.proposal(1, 9, SystemClock.elapsedRealtime()) != null else s.desktop?.optBoolean("observation_stable") == true && s.desktop?.getJSONArray("physical")?.length() == 1 }
                onView(withText("Settings")).perform(click())
                onView(withText("Confirm physical move")).perform(scrollTo(), click())
                }
                waitFor { if (standalone) s.game?.moves?.size == (if (automatic) 3 else 2) else s.desktop?.optInt("depth") == (if (automatic) 3 else 2) }
                val resulting = IntArray(361)
                val stones = if (standalone) org.json.JSONArray() else s.desktop!!.getJSONArray("stones")
                if (standalone) s.game!!.board.forEachIndexed { p, c -> resulting[BoardPosition.fullPoint(p, 9)] = c }
                for (n in 0 until stones.length()) {
                    val stone = stones.getJSONArray(n)
                    resulting[(stone.getInt(1) + 5) * 19 + stone.getInt(0) + 5] = if (stone.getString(2) == "B") 1 else 2
                }
                send(resulting)
                waitFor { if (standalone) s.sync.ready else s.desktop?.optBoolean("ready") == true }
                val acceptedDepth = if (standalone) s.game!!.moves.size else s.desktop!!.getInt("depth")
                for (color in 1..2) {
                    val original = resulting.indexOfFirst { it == color }
                    val extra = resulting.indices.first { resulting[it] == 0 && it != original && it in 120..240 }
                    val displaced = resulting.copyOf().apply { this[original] = 0; this[extra] = color }
                    send(displaced)
                    waitFor { s.position.observed?.contentEquals(displaced) == true && s.position.stable(SystemClock.elapsedRealtime()) }
                    assertNull(s.position.proposal(color, 9, SystemClock.elapsedRealtime()))
                    assertTrue(s.position.corrections(9).containsValue(color))
                    i.runOnMainSync {
                        try { s.confirmPhysicalMove(); fail("Displacement must never commit") } catch (_: IllegalStateException) {}
                    }
                    assertEquals(acceptedDepth, if (standalone) s.game!!.moves.size else s.desktop!!.getInt("depth"))
                    send(resulting)
                    waitFor { s.position.ready(SystemClock.elapsedRealtime()) && s.position.corrections(9).isEmpty() }
                }
                if (!standalone) assertNull(s.game) // Computer mode never commits through Android GoGame.
                val profile = if (standalone) "cloud" else "desktop"
                assertTrue(s.store.all().any { it.getString("profile") == profile })
                if (standalone) {
                    val event = Parcel.obtain(); val response = Parcel.obtain()
                    try { event.writeString("~WTK#"); mock!!.transact(107, event, response, 0); response.readException() }
                    finally { event.recycle(); response.recycle() }
                    waitFor { s.suggestions != null }
                    assertEquals(4, s.suggestions!!.length())
                    assertEquals(4, s.suggestionPreview().count { it != 0 })
                    assertTrue(s.suggestionPreview().indices.all { s.suggestionPreview()[it] == 0 || resulting[it] == 0 })
                    send(resulting.copyOf().apply { this[0] = 1 })
                    waitFor { s.suggestions == null }
                    send(resulting)
                    waitFor { s.sync.ready }
                    i.runOnMainSync { s.action("resign") }
                    val finalSgf = s.game!!.sgf()
                    send(IntArray(361))
                    Thread.sleep(800)
                    assertEquals(finalSgf, s.game!!.sgf())
                    assertTrue(s.finishedGame)
                    assertTrue(s.hardware.active)
                    var restarted = false
                    i.runOnMainSync { s.startGame(9, "B", false, "ai:default") { ok -> assertTrue(ok); restarted = true } }
                    waitFor { restarted && s.sync.ready }
                    assertFalse(s.finishedGame)
                    assertTrue(s.hardware.active)
                }
                if (!standalone) {
                    val busy = TrainingService::class.java.getDeclaredField("desktopBusy").apply { isAccessible = true }
                    var sent = false
                    waitFor {
                        if (!sent) i.runOnMainSync {
                            if (s.desktopConnected && s.desktop?.optBoolean("ready") == true && !busy.getBoolean(s)) {
                                s.action("resign"); sent = true
                            }
                        }
                        sent
                    }
                    waitFor { s.finishedGame }
                    send(IntArray(361))
                    assertTrue(s.hardware.active)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                waitFor { !s.hardware.active }
                i.runOnMainSync { s.disconnect() }
            } finally {
                i.runOnMainSync { service?.disconnect() }
                val clientField = BoardHardware::class.java.getDeclaredField("client").apply { isAccessible = true }
                waitFor { service == null || clientField.get(service!!.hardware) == null }
                context.unbindService(serviceConnection); context.unbindService(mockConnection)
                for (record in GameStore(context).all().filter { it.getString("id") !in originalIds }) {
                    android.util.AtomicFile(File(context.filesDir, "games/${record.getString("profile")}-${record.getString("id")}.json")).delete()
                }
            }
        }
    }
}
