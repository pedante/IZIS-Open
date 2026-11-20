package cn.izis.izisbridge

import android.content.Context
import android.os.Handler
import android.os.Looper

internal fun wireIndex(point: Int, size: Int): Int {
    require(size in listOf(9, 13, 19) && point in 0 until size * size)
    return size * size - 1 - point
}

/** Manufacturer Binder owns all serial writes. */
class BoardHardware(private val context: Context, private val update: (String) -> Unit,
                    private val snapshot: (IntArray?) -> Unit, private val whiteLongPress: () -> Unit = {}) {
    private val diagnostics = java.util.ArrayDeque<String>()
    private var lastDiagnostic = ""
    internal fun diagnostic(event: String) {
        if (event == lastDiagnostic) return
        lastDiagnostic = event
        diagnostics.addLast("${android.os.SystemClock.elapsedRealtime()} $event")
        while (diagnostics.size > 128) diagnostics.removeFirst()
        try {
            val trace = diagnostics.joinToString("\n")
            java.io.File(context.filesDir, "lighting-diagnostics.txt").writeText(trace)
            if (event.startsWith("LED cleanup unconfirmed")) java.io.File(context.filesDir, "lighting-failure.txt").writeText(trace)
        }
        catch (_: java.io.IOException) { update("Lighting diagnostic storage unavailable") }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = false
        private set
    val released get() = client == null
    var observed: IntArray? = null
        private set
    private var client: BoardServiceClient? = null
    private var leds: LedController? = null
    private var stopping = false
    private var stopAt = 0L
    private var nextSnapshot = 0L
    private var lastSnapshot = 0L
    private val buttons = ClockButtons()
    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (client?.ready() == true) leds?.tick(now, observed)
            if (lastSnapshot != 0L && now - lastSnapshot > 30_000) {
                lastSnapshot = 0; observed = null; leds?.display(emptyMap()); snapshot(null)
                update("Sensor snapshot expired; reconcile before continuing")
            }
            if (stopping && (leds?.clean() == true || now >= stopAt)) {
                if (leds?.clean() != true) leds?.lost("release deadline without ALC")
                diagnostic("unbind requested clean=${leds?.clean() == true}"); client?.disconnect(); return
            }
            if (active && client?.ready() == true && now >= nextSnapshot) {
                nextSnapshot = now + 1000; client?.send("~STA#")
            }
            if (client != null) main.postDelayed(this, 100)
        }
    }
    fun start(size: Int, forward: Boolean = false, port: Int = 5000, desktopHost: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!active && client == null) { "Wait for client cleanup" }
        require(size in listOf(9, 13, 19))
        check(!forward) { "Serial forwarding is prohibited" }
        active = true; stopping = false; nextSnapshot = 0; lastSnapshot = 0
        val uncertain = leds?.clean() == false
        leds = LedController({ command -> diagnostic("dispatch $command"); client?.send(command) }, { event ->
            diagnostic(event)
            if (event.startsWith("LED cleanup")) update(event)
        }).also { if (uncertain) { it.lost("previous disconnect"); it.cleanup() } }
        client = BoardServiceClient(context, object : BoardServiceClient.Observer {
            override fun status(text: String, busy: Boolean) {
                diagnostic("service $text")
                update(if (!busy && stopping && leds?.clean() != true) "LED cleanup unconfirmed — $text" else text)
                if (!busy) {
                    if (leds?.clean() != true) leds?.lost("Service disconnected")
                    active = false; observed = null; leds?.display(emptyMap()); snapshot(null)
                    main.removeCallbacks(tick)
                    val released = client; client = null; released?.destroy()
                }
            }
            override fun message(text: String) {
                if (text.startsWith("TX failed:") || text.startsWith("RX error:") || text.contains("success=false")) {
                    diagnostic(text.take(200))
                    if (active) { observed = null; snapshot(null) }
                    return
                }
                if (text.startsWith("TX IPC completed") && text.endsWith("~RGC#")) diagnostic(text)
                if (!text.startsWith("RX: ")) return
                val frame = text.removePrefix("RX: ")
                leds?.receive(frame)
                if (active && buttons.accept(frame, android.os.SystemClock.elapsedRealtime())) whiteLongPress()
                if (!active || !frame.startsWith("~SDA")) return
                try {
                    observed = BoardPosition.decode(frame)
                    lastSnapshot = android.os.SystemClock.elapsedRealtime()
                    snapshot(observed?.copyOf())
                } catch (_: IllegalArgumentException) {
                    observed = null; leds?.display(emptyMap())
                    snapshot(null); update("Invalid complete sensor snapshot")
                }
            }
        }).also { it.connect() }
        main.post(tick)
    }
    fun display(points: Map<Int, Int>, size: Int, suggestions: IntArray = IntArray(0)) {
        if (!active || stopping) return
        val board = observed ?: run { leds?.display(emptyMap()); return }
        val pattern = linkedMapOf<Int, String>()
        BoardPosition.boundary(size).forEachIndexed { p, boundary -> if (boundary) pattern[361 - p] = LedController.GREEN }
        suggestions.forEachIndexed { p, rgb -> if (rgb != 0 && board[p] == 0) pattern[361 - p] = LedController.RED }
        // Guide one missing stone at a time. The screen lists all outstanding corrections.
        val indicator = (points.entries.firstOrNull { board[BoardPosition.fullPoint(it.key, size)] == 0 } ?: points.entries.firstOrNull())?.let {
            (361 - BoardPosition.fullPoint(it.key, size)) to if (it.value == 1) LedController.GREEN else LedController.WHITE
        }
        leds?.display(pattern, indicator)
    }
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        if (stopping || client == null) return
        active = false; stopping = true; observed = null; snapshot(null)
        val now = android.os.SystemClock.elapsedRealtime()
        diagnostic("cleanup requested"); client?.cancelCommands(); stopAt = now + 7000; leds?.cleanup()
    }
}
