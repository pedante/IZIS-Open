package cn.izis.izisbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

class TrainingService : Service() {
    inner class LocalBinder : Binder() { val service: TrainingService get() = this@TrainingService }
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    lateinit var credentials: Credentials
        private set
    lateinit var store: GameStore
        private set
    lateinit var cloud: Read19Connection
        private set
    lateinit var hardware: BoardHardware
        private set
    val desktopStatus = ProfileStatus()
    var desktop: JSONObject? = null
        private set
    private var desktopClient: DesktopConnection? = null
    var desktopConnected = false
        private set
    private var desktopBusy = false
    private var newGamePending = false
    private var starting = false
    private var endingGame = false
    var suggestions: JSONArray? = null
        private set
    private var suggestionRequested = false
    private var suggestionUntil = 0L
    val finishedGame: Boolean get() = if (profile == "cloud") game?.result != null else desktop?.optString("result").let { it != null && it.isNotEmpty() && it != "null" }
    private fun clearSuggestions() { suggestions = null; suggestionRequested = false; suggestSent = false }
    var automaticMoves = true
        private set
    fun setAutomaticMoves(value: Boolean) {
        automaticMoves = value
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("automaticMoves", value).apply()
    }
    private var desktopGeneration = 0
    private var savedDesktopRecord = ""
    private var clientId = ""
    var profile = "desktop"
        private set
    var game: GoGame? = null
        private set
    var analysis: JSONObject? = null
        private set
    var hardwareStatus = "Disconnected — Service development build"
        private set
    var notice = ""
        private set
    var storageError = false
        private set
    var review = false
        private set
    private var reviewSource: JSONObject? = null
    private var reviewIndex = 0
    val sync = PhysicalSync()
    val position = PhysicalPosition()
    private var stream = UUID.randomUUID().toString()
    private var sensorSequence = 0

    var changed: (() -> Unit)? = null
    private var queryId: String? = null
    private var queryRevision = 0
    private var revision = 0
    private var failedRevision = -1
    private var priorAnalysis: JSONObject? = null
    private var suggestSent = false
    private var runningGame = false
    private var foreground = false
    private var releasing = false
    private var wantProvider = false
    private var boardAttempted = false
    private var reconnectAt = 0L
    private var stopping = false
    fun visibility(visible: Boolean) {
        if (foreground == visible) return
        hardware.diagnostic("foreground=$visible")
        if (visible) exitRequested = false
        foreground = visible
        if (visible) { boardAttempted = false; maintainOwnership() }
        else {
            val resume = wantProvider
            disconnect()
            wantProvider = resume
        }
    }
    private fun maintainOwnership() {
        if (!foreground || releasing) return
        if (!boardAttempted && hardware.released) {
            boardAttempted = true
            startBoard()
        }
        if (!wantProvider || starting || desktopBusy || SystemClock.elapsedRealtime() < reconnectAt) return
        if (profile == "desktop" && !desktopConnected || profile == "cloud" && !cloud.connected && cloud.status.state != ConnectionState.CONNECTING) {
            reconnectAt = SystemClock.elapsedRealtime() + 5000
            try { connect() } catch (e: IllegalStateException) { wantProvider = false; notice = e.message ?: "Provider unavailable" }
        }
    }
    private var exitRequested = false
    fun requestExit() {
        visibility(false)
        exitRequested = true
        hardware.stop()
    }
    private var pollTicks = 0
    private var usageRefreshScheduled = false
    private val usageAfterAnalysis = Runnable {
        usageRefreshScheduled = false
        if (cloud.connected) cloud.refreshUsage()
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (stopping) return
            if (exitRequested && hardware.released && !releasing) { stopSelf(); return }
            maintainOwnership()
            cloud.checkTimeout()
            if (SystemClock.elapsedRealtime() >= suggestionUntil || (profile == "cloud" && !cloud.connected) || (profile == "desktop" && !desktopConnected)) clearSuggestions()
            publishSuggestions()
            updateLights()
            changed?.invoke()
            sync.fresh(SystemClock.elapsedRealtime())
            if (desktopConnected && !desktopBusy && !newGamePending && !endingGame && pollTicks % 2 == 0) {
                val board = hardware.observed
                if (hardware.active && board != null) desktopCommand("observe", JSONObject()
                    .put("physical", JSONArray(board.toList())).put("stream", stream).put("sequence", ++sensorSequence))
                else desktopCommand("status")
            }
            if (profile == "cloud" && runningGame) {
                if (pollTicks % 120 == 0) cloud.refreshUsage()
                advanceCloud()
            }
            pollTicks++
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        credentials = Credentials(this); store = GameStore(this)
        cloud = Read19Connection({ handler.post { persistUsage(); changed?.invoke() } }, { id, result -> handler.post { analysisFinished(id, result) } })
        hardware = BoardHardware(this, { value -> handler.post { hardwareStatus = value; changed?.invoke() } },
            { board -> handler.post { physical(board) } }, { handler.post { requestSuggestions() } })
        try {
            clientId = credentials.get("client") ?: UUID.randomUUID().toString().also { credentials.put("client", it) }
            credentials.get("cloud")?.let { cloud.configure(it) }
            credentials.get("desktop")?.let { configureDesktop(JSONObject(it), false) }
        } catch (_: Exception) { notice = "Secure storage unavailable. Replace credentials; no secrets were recovered." }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        profile = prefs.getString("profile", "desktop") ?: "desktop"
        automaticMoves = prefs.getBoolean("automaticMoves", true)
        cloud.status.lastCheck = prefs.getLong("cloudCheck", 0)
        desktopStatus.lastCheck = prefs.getLong("desktopCheck", 0)
        prefs.getString("usage", null)?.let {
            try {
                cloud.lastUsage = JSONObject(it); cloud.usageTime = prefs.getLong("usageTime", 0)
                cloud.status.quota = usageText(cloud.lastUsage!!) + "\nLast successful value retained"
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("board", "Izis training connection", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrainingService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, NotificationCompat.Builder(this, "board").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Izis Go training").setContentText("Tap to return; Stop releases board access")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
        handler.post(heartbeat)
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") requestExit()
        return START_NOT_STICKY
    }
    private fun persistUsage() {
        if (!::cloud.isInitialized) return
        if (!cloud.connected && profile == "cloud") clearSuggestions()
        if (cloud.connected && profile == "cloud") getSharedPreferences("ui", MODE_PRIVATE).edit().putString("successfulProfile", profile).apply()
        getSharedPreferences("ui", MODE_PRIVATE).edit().putLong("cloudCheck", cloud.status.lastCheck)
            .putLong("desktopCheck", desktopStatus.lastCheck).apply()
        cloud.lastUsage?.let { value ->
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("usage", value.toString()).putLong("usageTime", cloud.usageTime).apply()
        }
    }
    fun selectProfile(value: String) {
        require(value in listOf("cloud", "desktop"))
        check(!desktopConnected && !cloud.connected && !desktopBusy) { "Disconnect before changing profile" }
        cloud.disconnect()
        profile = value
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("profile", value).apply()
        runningGame = false; changed?.invoke()
    }
    fun configureCloud(value: String) {
        check(!runningGame && !cloud.connected) { "Disconnect before replacing credentials" }
        val valid = validateCloudCredential(value)
        credentials.put("cloud", valid); cloud.configure(valid)
        getSharedPreferences("ui", MODE_PRIVATE).edit().remove("usage").remove("usageTime").remove("cloudCheck").apply()
        changed?.invoke()
    }
    fun configureDesktop(value: JSONObject, save: Boolean = true) {
        check(!desktopConnected && !desktopBusy) { "Disconnect before replacing pairing" }
        val connection = DesktopConnection(value.getString("host"), value.getInt("port"), value.getString("fingerprint"), value.getString("token"))
        if (save) credentials.put("desktop", value.toString())
        desktopClient = connection
        desktopStatus.apply { state = ConnectionState.UNTESTED; configuration = "Configured"; message = ""; lastCheck = 0 }
        changed?.invoke()
    }
    fun connect() {
        wantProvider = true
        if (profile == "cloud") {
            runningGame = !starting && game != null && !review && !storageError && game?.result == null
            failedRevision = -1
            cloud.connect(); cloud.refreshUsage(true)
        }
        else desktopCommand("acquire")
    }
    fun checkConnection() {
        if (profile == "cloud") { cloud.connect(); cloud.refreshUsage(true) }
        else desktopCommand("status")
    }
    fun testAnalysis() {
        if (profile == "desktop") desktopCommand("test_analysis")
        else {
            check(queryId == null) { "Wait for the current analysis" }
            queryRevision = -1
            queryId = cloud.analyze(GoGame(9).query(2))
        }
    }
    fun refreshUsage() { cloud.refreshUsage(true) }
    fun startBoard() {
        check(!hardware.active) { "Board already active" }

        
        sync.reset(); position.reset(); stream = UUID.randomUUID().toString(); sensorSequence = 0
        hardware.start(if (profile == "cloud") game?.size ?: 19 else desktop?.optJSONArray("board_size")?.optInt(0) ?: 19,
            false)

    }
    fun disconnect(done: () -> Unit = {}) {
        releasing = true; wantProvider = false; hardware.stop(); position.reset(); sync.reset()
        clearSuggestions(); starting = false
        runningGame = false; cloud.disconnect(); queryId = null
        desktopGeneration++
        desktopConnected = false
        val releaseDesktop = profile == "desktop" && desktopClient != null && (desktop != null || desktopBusy)
        io.execute {
            if (releaseDesktop) {
                try { desktopClient?.request(envelope("release")) } catch (_: Exception) {}
            }
            handler.post {
                releasing = false
                sync.reset(); desktopStatus.state = if (desktopClient == null) ConnectionState.NOT_CONFIGURED else ConnectionState.DISCONNECTED
                desktopStatus.network = "Disconnected"; desktopBusy = false
                changed?.invoke(); done()
            }
        }
    }
    private fun envelope(op: String): JSONObject = JSONObject().put("op", op).put("id", UUID.randomUUID().toString()).put("client", clientId)
    fun desktopCommand(op: String, extra: JSONObject = JSONObject(), completed: ((Boolean) -> Unit)? = null) {
        if (releasing || !foreground) { completed?.invoke(false); return }
        if (op !in listOf("status", "acquire", "library") && (!desktopConnected || desktop?.has("controller_epoch") != true)) {
            notice = "Connect the SmartGo desktop controller first"; changed?.invoke(); completed?.invoke(false); return
        }
        val connection = desktopClient ?: run { notice = "Configure desktop pairing first"; changed?.invoke(); completed?.invoke(false); return }
        if (desktopBusy) { notice = "Desktop request in progress"; changed?.invoke(); completed?.invoke(false); return }
        desktopBusy = true
        val version = desktopGeneration
        val request = envelope(op)
        desktop?.let { request.put("revision", it.optInt("revision")).put("game_id", it.optString("game_id")).put("controller_epoch", it.optString("controller_epoch")) }
        extra.keys().forEach { request.put(it, extra.get(it)) }
        if (op in listOf("acquire", "status") && !desktopConnected) desktopStatus.state = ConnectionState.CONNECTING
        changed?.invoke()
        io.execute {
            try {
                val result = connection.request(request)
                handler.post {
                    if (version != desktopGeneration) { notice = "Request interrupted; reconnect to reconcile its outcome"; completed?.invoke(false); return@post }
                    try {
                        if (op == "library") {
                            val games = result.getJSONArray("games")
                            for (i in 0 until games.length()) store.save(games.getJSONObject(i), "desktop")
                        } else {
                            if (result.optInt("protocol") != 1) throw UnsupportedOperationException()
                            val record = result.getJSONObject("record")
                            val encoded = record.toString()
                            if (encoded != savedDesktopRecord) {
                                store.save(record, "desktop")
                                savedDesktopRecord = encoded
                            }
                            check(result.has("controller_epoch")) { "Use the SmartGo desktop controller" }
                            if (desktop?.optInt("revision") != result.optInt("revision") || result.optBoolean("review")) clearSuggestions()
                            if (op == "acquire") {
                                position.reset(); stream = UUID.randomUUID().toString(); sensorSequence = 0
                            }
                            if (desktopConnected && result.optString("controller") != "touchscreen") {
                                desktopConnected = false; position.reset()
                                notice = "Computer control lost. Reconnecting to reconcile the game."
                            }
                            desktop = result
                            val full = IntArray(361)
                            val size = result.getJSONArray("board_size").getInt(0)
                            val stones = result.getJSONArray("stones")
                            for (i in 0 until stones.length()) {
                                val stone = stones.getJSONArray(i)
                                full[BoardPosition.fullPoint(stone.getInt(1) * size + stone.getInt(0), size)] = if (stone.getString(2) == "B") 1 else 2
                            }
                            position.accept(full, result.getString("controller_epoch") + result.getString("game_id") + ":" + result.getInt("revision"))
                            if (op != "acquire") position.observe(hardware.observed, SystemClock.elapsedRealtime())
                            updateLights()
                            desktopStatus.apply {
                                state = if (result.optString("analysis") == "ready") ConnectionState.READY else ConnectionState.CONNECTED
                                network = "Computer reachable"; authentication = "Paired and authenticated"
                                analysis = "KaTrain: ${result.optBoolean("katrain")} · KataGo available: ${result.optBoolean("engine")} · ${result.optString("analysis")} · Test: ${result.optString("test_analysis")}"
                                message = result.optString("status"); lastCheck = System.currentTimeMillis()
                            }
                            if (op == "acquire") { desktopConnected = true; getSharedPreferences("ui", MODE_PRIVATE).edit().putString("successfulProfile", "desktop").apply() }
                        }
                        notice = result.optJSONObject("proposal")?.let {
                            if (it.optBoolean("accepted")) "" else it.optString("reason", "Move rejected; restore the accepted position")
                        } ?: ""
                        storageError = false
                    } catch (_: UnsupportedOperationException) {
                        desktopStatus.state = ConnectionState.UNSUPPORTED
                        notice = "Unsupported desktop protocol; update the desktop integration"
                        desktopStatus.message = notice
                        storageError = true
                    } catch (_: Exception) {
                        notice = "Unable to validate or save desktop state; controls paused"
                        storageError = true
                    }
                    desktopBusy = false; persistUsage(); changed?.invoke(); completed?.invoke(!storageError)
                    if (op == "observe" && foreground) autoSubmit()
                }
            } catch (e: Exception) {
                handler.post {
                    if (version != desktopGeneration) { notice = "Request interrupted; reconnect to reconcile its outcome"; completed?.invoke(false); return@post }
                    desktopBusy = false
                    position.reset(); clearSuggestions(); reconnectAt = SystemClock.elapsedRealtime() + 2500
                    desktopConnected = false // Reacquire authoritative state before any further mutation.
                    val message = e.message.orEmpty()
                    desktopStatus.state = when {
                        message.startsWith("Authentication") -> ConnectionState.AUTH_ERROR
                        e is javax.net.ssl.SSLException -> ConnectionState.AUTH_ERROR
                        e is java.io.IOException -> ConnectionState.DISCONNECTED
                        else -> ConnectionState.BUSY
                    }
                    notice = when (e) {
                        is javax.net.ssl.SSLException -> "Certificate verification failed. Check the desktop pairing fingerprint."
                        is java.io.IOException -> if (op in listOf("new", "physical_proposal", "resign", "finish")) "Request outcome unknown; reconnect to reconcile before continuing." else "Computer unreachable; saved game retained. Check the network."
                        else -> message.take(200)
                    }
                    desktopStatus.message = notice; changed?.invoke(); completed?.invoke(false)
                }
            }
        }
    }
    fun startAgainst(opponent: String, size: Int, color: String, teaching: Boolean, difficulty: String, completed: (Boolean) -> Unit) {
        fun start() {
            if (!foreground) { completed(false); return }
            if (profile != opponent) selectProfile(opponent)
            startGame(size, color, teaching, difficulty, completed)
        }
        if (profile != opponent) disconnect { start() } else start()
    }
    fun startGame(size: Int, color: String, teaching: Boolean, difficulty: String, completed: (Boolean) -> Unit) {
        check(!starting && !newGamePending && !endingGame) { "Wait for the current game request to finish" }
        check(!storageError) { "Restore storage first" }
        if (profile == "desktop" && desktopClient == null || profile == "cloud" && cloud.status.configuration != "Configured" && !cloud.connected) {
            notice = if (profile == "desktop") "Set up Local Computer pairing in Settings" else "Set up Read19 credentials in Settings"
            completed(false); return
        }
        wantProvider = true; starting = true
        val deadline = SystemClock.elapsedRealtime() + 30000
        val generation = desktopGeneration
        var attemptedBoard = false
        fun fail(message: String) { starting = false; notice = message; changed?.invoke(); completed(false) }
        try { if (profile == "desktop" && !desktopConnected || profile == "cloud" && !cloud.connected) connect() }
        catch (e: Exception) { fail(e.message ?: "Connection failed"); return }
        handler.post(object : Runnable {
            override fun run() {
                if (!starting || generation != desktopGeneration || SystemClock.elapsedRealtime() > deadline) {
                    fail("Startup stopped; check engine connection and fresh board sensors in Settings"); return
                }
                val connected = if (profile == "desktop") desktopConnected else cloud.connected
                if (!connected || desktopBusy) { handler.postDelayed(this, 100); return }
                try {
                    if (!hardware.active) {
                        if (!hardware.released) { handler.postDelayed(this, 100); return }
                        if (attemptedBoard) { fail("Board unavailable: $hardwareStatus"); return }
                        attemptedBoard = true
                        startBoard()
                    }
                    val observed = hardware.observed
                    if (observed == null) {
                        notice = "Waiting for fresh board sensors"; changed?.invoke(); handler.postDelayed(this, 100); return
                    }
                    if (observed.any { it != 0 }) { fail("Remove all stones from the board, then tap Start again."); return }
                    if (profile == "desktop" && (desktop?.optBoolean("observation_stable") != true ||
                        desktop?.optJSONArray("physical")?.length() != 0)) {
                        handler.postDelayed(this, 100); return
                    }
                    starting = false
                    newGame(size, color, teaching, difficulty) { ok ->
                        if (ok) { stream = UUID.randomUUID().toString(); sensorSequence = 0 }
                        completed(ok)
                    }
                } catch (e: Exception) { fail(e.message ?: "Startup failed") }
            }
        })
    }
    fun requestSuggestions() {
        if (suggestionRequested || finishedGame || review || !hardware.active || position.differs() || hardware.observed == null) return
        suggestionRequested = true; suggestionUntil = SystemClock.elapsedRealtime() + 30000
        try {
            if (profile == "cloud") { if (!cloud.connected) { clearSuggestions(); return }; if (analysis == null && queryId == null) requestGameAnalysis() }
            publishSuggestions()
        } catch (_: Exception) { clearSuggestions(); notice = "Suggestions unavailable; check analysis status" }
        changed?.invoke()
    }
    private fun publishSuggestions() {
        if (!suggestionRequested) return
        if (profile == "desktop" && desktopConnected && !desktopBusy && !suggestSent && desktop?.optBoolean("current_analysis_complete") != true) {
            suggestSent = true
            desktopCommand("suggest") { if (!it) clearSuggestions() }
        }
        val candidates = if (profile == "cloud") analysis?.optJSONArray("moveInfos") else if (desktop?.optBoolean("current_analysis_complete") == true) desktop?.optJSONArray("candidate_details") else null
        suggestions = candidates?.let { source -> JSONArray().also { out ->
            for (i in 0 until source.length()) {
                val candidate = source.getJSONObject(i)
                if (profile == "cloud") {
                    val g = game ?: break
                    try { val p = g.point(candidate.getString("move")); if (p == null) continue; g.after(p) } catch (_: Exception) { continue }
                }
                if (candidate.optString("move").lowercase() in listOf("pass", "resign")) continue
                out.put(candidate); if (out.length() == 4) break
            }
        } }
    }
    /** Full-grid points for the numbered timer candidates. */
    fun suggestionPreview(): IntArray {
        val pattern = IntArray(361)
        if (position.differs() || finishedGame || review || !suggestionRequested) return pattern
        val size = if (profile == "cloud") game?.size ?: return pattern else desktop?.optJSONArray("board_size")?.optInt(0) ?: return pattern
        val occupied = hardware.observed ?: return pattern
        val candidates = suggestions ?: return pattern
        for (i in 0 until candidates.length()) {
            val move = candidates.getJSONObject(i).optString("move")
            val x = "ABCDEFGHJKLMNOPQRST".indexOf(move.firstOrNull() ?: ' ')
            val y = (move.drop(1).toIntOrNull() ?: 0) - 1
            if (x !in 0 until size || y !in 0 until size) continue
            val point = BoardPosition.fullPoint(y * size + x, size)
            if (occupied[point] == 0) pattern[point] = 0x080000
        }
        return pattern
    }
    fun newGame(size: Int, color: String, teaching: Boolean, difficulty: String, completed: (Boolean) -> Unit = {}) {
        check(!starting && !newGamePending) { "New game already pending" }
        fun finished(ok: Boolean) {
            newGamePending = false
            if (ok) notice = ""
            if (ok) getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("$profile.size", size).putString("$profile.color", color).putBoolean("$profile.teaching", teaching).putString("$profile.difficulty", difficulty).apply()
            completed(ok)
        }
        if (profile == "desktop") {
            check(!storageError)
            check(desktopConnected) { "Connect the computer first" }
            newGamePending = true
            val generation = desktopGeneration
            val until = SystemClock.elapsedRealtime() + 15000
            val send = object : Runnable {
                override fun run() {
                    if (generation != desktopGeneration || !desktopConnected || SystemClock.elapsedRealtime() > until) {
                        notice = "New game not sent; reconnect and try again"; finished(false); return
                    }
                    if (desktopBusy) { handler.postDelayed(this, 100); return }
                    desktopCommand("new", JSONObject().put("size", size).put("color", color).put("mode", if (teaching) "teaching" else "normal").put("difficulty", difficulty).put("engine_profile", if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("fastTesting", false)) "fast" else "full"), ::finished)
                }
            }
            handler.post(send)
            return
        }
        check(!hardware.active || hardware.observed?.all { it == 0 } == true) { "Remove all stones from the board before starting a new game" }
        check(!storageError) { "Restore storage before starting a new game" }
        check(difficulty == "ai:default") { "Standalone difficulty is full strength only" }
        val human = when (color) { "B" -> 1; "W" -> 2; "random" -> if (java.security.SecureRandom().nextBoolean()) 1 else 2; else -> error("Invalid color") }
        position.reset()
        game = GoGame(size, human, teaching); review = false; reviewSource = null
        runningGame = true; positionChanged()
        sync.reset()
        cloud.refreshUsage(); finished(!storageError)
    }
    fun openSaved(record: JSONObject, reviewing: Boolean) {
        if (record.getString("profile") == "desktop") {
            check(profile == "desktop") { "Select the desktop profile to resume or analyze this KaTrain game" }
            desktopCommand(if (reviewing) "review" else "resume", JSONObject().put("saved_id", record.getString("id")))
            return
        }
        check(profile == "cloud") { "Select Read19 Cloud to open this game" }
        check(game == null || finishedGame || !hardware.active || sync.fresh(SystemClock.elapsedRealtime())) { "Resolve physical correction first" }
        val restored = GoGame.restore(record)
        check(reviewing || restored.result == null) { "Finished game: use Review" }
        game = restored; review = reviewing; reviewSource = if (reviewing) record else null
        priorAnalysis = null
        reviewIndex = restored.moves.size; runningGame = !reviewing; positionChanged(save = false)
        sync.reset()
        if (hardware.active) hardware.stop()
        cloud.refreshUsage()
    }
    fun reviewStep(delta: Int) {
        if (profile == "desktop") { desktopCommand(if (delta < 0) "previous" else "next"); return }
        val source = reviewSource ?: return
        check(!hardware.active || sync.fresh(SystemClock.elapsedRealtime())) { "Correct the physical board before navigating" }
        val restored = GoGame.restore(source)
        reviewIndex = (reviewIndex + delta).coerceIn(0, restored.moves.size)
        restored.undo(restored.moves.size - reviewIndex)
        game = restored; positionChanged(false); sync.reset()
    }
    fun analyzePosition() {
        if (profile == "desktop") desktopCommand("test_analysis")
        else { failedRevision = -1; requestGameAnalysis() }
    }
    private fun positionChanged(save: Boolean = true) {
        clearSuggestions()
        revision++; analysis = null; suggestSent = false; failedRevision = -1
        sync.reset()
        if (save) saveGame()
        acceptGamePosition(); updateLights()
        changed?.invoke()
    }
    private fun saveGame() {
        val current = game ?: return
        try { store.save(current.json(), "cloud"); storageError = false }
        catch (_: Exception) { storageError = true; runningGame = false; notice = "Autosave failed. Free storage; game remains in memory. Tap Retry save before continuing." }
    }
    fun retrySave() {
        check(profile == "cloud" && !review && game != null) { "Open the active standalone game to retry saving" }
        saveGame(); if (!storageError) runningGame = true; changed?.invoke()
    }
    fun action(op: String, result: String? = null, completed: ((Boolean) -> Unit)? = null) {
        check(!finishedGame) { "Finished game — start a new game or review the saved record" }
        val ending = op == "resign" || op == "finish"
        check(!endingGame) { "Waiting for the game result to be saved" }
        if (profile == "desktop") {
            check(!storageError) { "Storage unavailable" }
            val extra = JSONObject().apply { if (result != null) put("result", result) }
            if (!ending) { desktopCommand(op, extra, completed); return }
            endingGame = true
            val generation = desktopGeneration
            val deadline = SystemClock.elapsedRealtime() + 10_000
            fun finished(ok: Boolean) { endingGame = false; completed?.invoke(ok); changed?.invoke() }
            handler.post(object : Runnable {
                override fun run() {
                    if (generation != desktopGeneration || SystemClock.elapsedRealtime() > deadline) {
                        notice = "The game result was not sent. Reconnect and try again."
                        finished(false); return
                    }
                    if (desktopBusy) { handler.postDelayed(this, 100); return }
                    desktopCommand(op, extra, ::finished)
                }
            })
            return
        }
        val current = game ?: error("Start or resume a game")
        check(!review && !storageError) { "Open an active game with working storage" }
        check(ending || sync.fresh(SystemClock.elapsedRealtime())) { "Resolve physical correction before changing the game" }
        when (op) {
            "pass" -> { check(current.next == current.human) { "Wait for the engine turn" }; priorAnalysis = analysis; current.play(null) }
            "undo" -> current.undo(if (current.moves.lastOrNull()?.color != current.human && current.moves.size > 1) 2 else 1)
            "resign" -> current.result = "${if (current.human == 1) "W" else "B"}+R"
            "finish" -> {
                require(result != null && result.matches(Regex("(?:[BW]\\+(?:R|[0-9]+(?:\\.[0-9]+)?)|0|Void)"))) { "Enter a valid agreed result" }
                current.result = result
            }
            else -> error("Unsupported command")
        }
        positionChanged()
        completed?.invoke(!storageError)
    }
    fun setThreshold(value: Double) {
        require(value.isFinite() && value in 0.0..100.0)
        check(!review && !storageError) { "Change teaching settings in an active game" }
        val current = game ?: error("Start a teaching game")
        current.threshold = value; saveGame(); changed?.invoke()
    }
    private fun acceptGamePosition() {
        val current = game ?: return
        val full = IntArray(361)
        current.board.forEachIndexed { p, c -> full[BoardPosition.fullPoint(p, current.size)] = c }
        position.accept(full, current.id + ":" + revision)
    }
    private fun physical(board: IntArray?) {
        val now = SystemClock.elapsedRealtime()
        if (board == null || position.observed?.contentEquals(board) != true) clearSuggestions()
        if (finishedGame) { clearSuggestions(); updateLights(); changed?.invoke(); return }
        if (profile == "cloud") {
            val current = game ?: return
            acceptGamePosition()
            position.observe(board, now)
            val cropped = board?.let { source -> IntArray(current.size * current.size) { source[BoardPosition.fullPoint(it, current.size)] } }
            val inside = (0 until current.size * current.size).map { BoardPosition.fullPoint(it, current.size) }.toSet()
            val outside = board?.indices?.any { it !in inside && board[it] != 0 } == true
            sync.update(current, if (outside) null else cropped, now, false)
            updateLights(); autoSubmit(); advanceCloud()
        } else {
            position.observe(board, now)
            updateLights()
        }
        changed?.invoke()
    }
    private fun autoSubmit() {
        if (!foreground || starting || endingGame || releasing || !automaticMoves || storageError || !hardware.active || newGamePending) return
        if (profile == "desktop" && (!desktopConnected || desktopBusy)) return
        if (profile == "cloud" && !cloud.connected) return
        try { confirmPhysicalMove() } catch (_: IllegalStateException) { /* Not a stable human proposal yet. */ }
        catch (_: IllegalArgumentException) {
            position.submitted(); notice = "Illegal move; restore the accepted position"; changed?.invoke()
        }
    }
    fun confirmPhysicalMove() {
        check(!storageError && hardware.active) { "Connect the board and restore storage first" }
        val now = SystemClock.elapsedRealtime()
        if (profile == "desktop") {
            check(desktopConnected && !newGamePending) { "Reconnect and reconcile first" }
            val state = desktop ?: error("Connect KaTrain first")
            check(!state.optBoolean("review") && state.optString("result").let { it.isEmpty() || it == "null" }) { "No active game" }
            check(state.getJSONObject("record").getJSONObject("players").getJSONObject(state.getString("next_player")).getString("player_type") == "player:human") { "Wait for your turn" }
            val size = state.getJSONArray("board_size").getInt(0)
            val color = state.getString("next_player")
            val point = position.proposal(if (color == "B") 1 else 2, size, now) ?: error("Wait for one stable valid placement")
            check(!desktopBusy && state.optBoolean("observation_stable")) { "Wait for desktop sensor synchronization" }
            val remotePhysical = IntArray(361)
            val reported = state.getJSONArray("physical")
            for (i in 0 until reported.length()) {
                val stone = reported.getJSONArray(i)
                val x = stone.getInt(0) + (19 - size) / 2
                val y = stone.getInt(1) + (19 - size) / 2
                check(x in 0..18 && y in 0..18)
                remotePhysical[y * 19 + x] = if (stone.getString(2) == "B") 1 else 2
            }
            check(remotePhysical.contentEquals(position.observed)) { "Wait for the desktop to receive this placement" }
            position.submitted()
            desktopCommand("physical_proposal", JSONObject().put("point", JSONArray(listOf(point % size, point / size)))
                .put("color", color).put("physical", JSONArray(position.observed!!.toList())))
        } else {
            val current = game ?: error("Start a game")
            check(cloud.connected && runningGame && !review && current.result == null && !current.twoPasses && current.next == current.human) { "Wait for your turn" }
            val point = position.proposal(current.human, current.size, now) ?: error("Wait for one stable valid placement")
            val observed = position.observed!!
            priorAnalysis = analysis; current.play(point); position.submitted(); positionChanged()
            physical(observed)
        }
    }
    private fun requestGameAnalysis() {
        val current = game ?: return
        check(cloud.connected && queryId == null) { "Connect Read19 and wait for current analysis" }
        queryRevision = revision
        queryId = cloud.analyze(current.query().put("overrideSettings", JSONObject().put("reportAnalysisWinratesAs", "BLACK")))
    }
    private fun advanceCloud() {
        val current = game ?: return
        if (!runningGame || review || storageError || current.result != null || !cloud.connected) return
        if (analysis == null && queryId == null && failedRevision != revision && sync.fresh(SystemClock.elapsedRealtime())) {
            try { requestGameAnalysis() } catch (_: Exception) { failedRevision = revision }
            return
        }
        if (current.next != current.human && !current.twoPasses && sync.fresh(SystemClock.elapsedRealtime()) && analysis != null) {
            val candidates = analysis!!.optJSONArray("moveInfos") ?: return
            for (i in 0 until candidates.length()) {
                try {
                    val move = current.point(candidates.getJSONObject(i).getString("move"))
                    current.play(move)
                    priorAnalysis = analysis; positionChanged(); updateLights(); return
                } catch (_: IllegalArgumentException) {}
            }
            notice = "No legal engine candidate; retry analysis"
        }
    }
    private fun analysisFinished(id: String, value: JSONObject?) {
        if (!usageRefreshScheduled) {
            usageRefreshScheduled = true
            handler.postDelayed(usageAfterAnalysis, 31_000)
        }
        if (id != queryId) return
        queryId = null
        if (queryRevision == -1) { notice = if (value != null) "Test analysis succeeded" else "Test analysis failed; see connection status"; changed?.invoke(); return }
        if (queryRevision != revision) return
        if (value == null) { clearSuggestions(); failedRevision = revision; notice = "Analysis unavailable; game saved. Reconnect and tap Analyze / retry."; changed?.invoke(); return }
        analysis = value
        val current = game ?: return
        if (current.teaching && !review && current.moves.lastOrNull()?.color == current.human) {
            val before = priorAnalysis?.optJSONObject("rootInfo")?.optDouble("scoreLead", Double.NaN) ?: Double.NaN
            val after = value.optJSONObject("rootInfo")?.optDouble("scoreLead", Double.NaN) ?: Double.NaN
            val loss = scoreLoss(before, after, current.human)
            if (loss != null) {
                current.feedback = "Last move lost %.1f points (estimate).".format(loss)
                if (loss > current.threshold) {
                    current.feedback += " Restore the previous board and try again."
                    val parent = priorAnalysis
                    current.undo(1); positionChanged(); analysis = parent; sync.reset()
                } else saveGame()
            }
        }
        updateLights(); advanceCloud(); changed?.invoke()
    }
    private fun updateLights() {
        if (!hardware.active) return
        val size = if (profile == "cloud") game?.size else desktop?.optJSONArray("board_size")?.optInt(0)
        val connected = if (profile == "cloud") cloud.connected else desktopConnected
        if (!foreground || starting || !connected || size == null || finishedGame || review || (profile == "desktop" && desktop?.optBoolean("review") == true)) {
            hardware.display(emptyMap(), 19)
            return
        }
        hardware.display(position.corrections(size), size, suggestionPreview())
    }
    override fun onTaskRemoved(rootIntent: Intent?) { requestExit() }
    override fun onDestroy() {
        stopping = true; desktopGeneration++; changed = null; cloud.disconnect()
        handler.removeCallbacksAndMessages(null)
        io.execute { hardware.stop() }; io.shutdown()
        super.onDestroy()
    }
}

internal fun scoreLoss(beforeBlack: Double, afterBlack: Double, player: Int): Double? =
    if (beforeBlack.isFinite() && afterBlack.isFinite()) max(0.0, (beforeBlack - afterBlack) * if (player == 1) 1 else -1) else null
