package cn.izis.izisbridge

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var service: TrainingService? = null
    private var visible = false
    private var bound = false
    private var page = "Match"
    private lateinit var body: LinearLayout
    private var statusView: TextView? = null
    private var detailsView: TextView? = null
    private var boardView: GoBoardView? = null
    private var exportSgf: String? = null
    private var libraryVersion = ""
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TrainingService.LocalBinder).service
            service!!.changed = { refresh() }; service!!.visibility(visible); build()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; build() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        page = savedInstanceState?.getString("page") ?: "Match"
        exportSgf = savedInstanceState?.getString("export")
        val intent = Intent(this, TrainingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        build()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putString("page", page); outState.putString("export", exportSgf)
    }
    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
    private fun text(value: String, parent: LinearLayout = body): TextView = TextView(this).apply {
        text = value; textSize = 16f; setPadding(0, dp(6), 0, dp(6)); parent.addView(this)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; minHeight = dp(48); setOnClickListener { safe(action) }; body.addView(this)
    }
    private fun safe(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Izis").setMessage(e.message?.take(240) ?: "Action unavailable").setPositiveButton("OK", null).show()
        }
    }
    private fun build() {
        val root = column()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }
        val nav = LinearLayout(this); root.addView(nav)
        for (name in listOf("Match", "Analysis", "Games", "Settings")) {
            nav.addView(Button(this).apply {
                text = name; textSize = 13f; minWidth = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { page = name; build() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        if (page == "Analysis") root.addView(Button(this).apply {
            text = "? Help — buttons and LED colors"; minHeight = dp(48); setOnClickListener { help() }
        })
        body = column()
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        statusView = null; detailsView = null; boardView = null
        val s = service ?: run { text("Starting Izis training…"); return }
        if (page != "Match") text("Izis · $page").textSize = 23f
        if (page == "Match") button("Start Game") { chooseOpponent(s) }
        statusView = text("").apply { if (page == "Match" || page == "Analysis") { setLines(2); ellipsize = android.text.TextUtils.TruncateAt.END } }
        when (page) { "Settings" -> settings(s); "Games" -> library(s); else -> match(s) }
        refresh()
    }
    private fun settings(s: TrainingService) {
        button("Physical board help") { help() }
        body.addView(CheckBox(this).apply { text = "Submit stable human moves automatically"; isChecked = s.automaticMoves; setOnCheckedChangeListener { _, value -> s.setAutomaticMoves(value) } })
        button("Confirm physical move") { s.confirmPhysicalMove() }
        button("Retry save") { s.retrySave() }
        text("Local engine profile")
        val engineProfile = spinner(listOf("Full strength", "Fast Testing — 64 visits / 1 second"), body)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        engineProfile.setSelection(if (prefs.getBoolean("fastTesting", false)) 1 else 0)
        engineProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putBoolean("fastTesting", position == 1).apply() }
        }
        button("Default game preferences") { chooseOpponent(s, true) }
        button("Configure / Replace Read19 credential") { credentialDialog(s) }
        button("Configure / Replace desktop pairing") { desktopDialog(s) }
        button("Discover local computer") {
            thread {
                val hosts = try { DesktopConnection.discover() } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (hosts.isEmpty()) AlertDialog.Builder(this).setMessage("No desktop discovered. Enable control port 8767, check the LAN, or enter the hostname manually.").setPositiveButton("OK", null).show()
                    else AlertDialog.Builder(this).setTitle("Select computer; verify its pairing fingerprint").setItems(hosts.toTypedArray()) { _, i -> desktopDialog(s, hosts[i]) }.show()
                }
            }
        }
        button("Disconnect to edit an active connection") { s.disconnect() }
        button("Test connection (no analysis)") { s.checkConnection() }
        button("Test analysis (uses a small amount of allowance)") { s.testAnalysis() }
        button("Refresh Read19 usage") { s.refreshUsage() }
        text("Computer mode uses the paired SmartGo desktop controller. Stable human placements submit automatically by default; KaTrain accepts moves and owns game history.")
    }
    private fun field(label: String, parent: LinearLayout, secret: Boolean = false, value: String = ""): EditText {
        text(label, parent)
        return EditText(this).apply {
            setSingleLine(true); setText(value); minHeight = dp(48)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = !secret; parent.addView(this)
        }
    }
    private fun form(title: String, fields: LinearLayout, submit: () -> Unit) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safe { submit(); dialog.dismiss(); refresh() } } }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun credentialDialog(s: TrainingService) {
        val fields = column()
        text("Prefer a kg_live_ analysis key with analysis:write and usage:read. Compatibility WSS URLs cannot supply usage.", fields)
        val key = field("Read19 credential (hidden)", fields, true)
        form("Read19 credential", fields) { s.configureCloud(key.text.toString()); key.text.clear() }
    }
    private fun desktopDialog(s: TrainingService, discovered: String = "") {
        val fields = column()
        val saved = s.credentials.get("desktop")?.let { JSONObject(it) }
        val host = field("Computer IP / hostname", fields, value = discovered.ifEmpty { saved?.optString("host").orEmpty() })
        val port = field("TLS control port", fields, value = (saved?.optInt("port") ?: 8767).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val pin = field("SHA-256 certificate fingerprint from desktop", fields, value = saved?.optString("fingerprint") ?: "")
        val token = field("Private pairing key (hidden)", fields, true)
        form("Pair with KaTrain", fields) {
            s.configureDesktop(JSONObject().put("host", host.text.toString().trim()).put("port", port.text.toString().toInt())
                .put("fingerprint", pin.text.toString().trim()).put("token", token.text.toString().trim().ifEmpty { saved?.getString("token") ?: "" }))
            token.text.clear()
        }
    }
    private fun help() {
            AlertDialog.Builder(this).setTitle("Physical board help")
                .setMessage("Dim green marks the board boundary and a missing black stone. White marks a missing white stone. Hold the white timer for up to four red suggestions. Match numbers the same intersections. Follow the screen to remove extra stones.")
                .setPositiveButton("OK", null).show()
    }
    private fun match(s: TrainingService) {
        boardView = GoBoardView(this)
        body.addView(boardView, LinearLayout.LayoutParams(-1, dp(minOf(resources.configuration.screenWidthDp - 48, (resources.configuration.screenHeightDp - 240).coerceAtLeast(240)))))
        if (page == "Match") {
            val row = LinearLayout(this); body.addView(row)
            for (label in listOf("Undo", "Pass", "Resign")) row.addView(Button(this).apply {
                text = label; minHeight = dp(48)
                setOnClickListener { safe {
                    when (label) {
                        "Pass" -> s.action("pass")
                        "Resign" -> resign(s)
                        else -> AlertDialog.Builder(this@MainActivity).setTitle("Undo this move?")
                            .setMessage("Return to the previous turn and reconcile the board.")
                            .setNegativeButton("Cancel", null).setPositiveButton(label) { _, _ -> safe { s.action("undo") } }.show()
                    }
                } }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        detailsView = text("")
        if (page == "Analysis") {
            text("Heat intensity shows relative policy probability; numbered circles are candidates. Outlines show required stones during correction.")
            button("Analyze / retry current position") { s.analyzePosition() }
            button("Show timer suggestions") { s.requestSuggestions() }
            button("Previous review move") { s.reviewStep(-1) }
            button("Next review move") { s.reviewStep(1) }
            button("Teaching thresholds") { thresholds(s) }
        } else {
            button("Score / end game") { scoring(s) }

        }
    }
    private fun resign(s: TrainingService) {
        val dialog = AlertDialog.Builder(this).setTitle("Resign this game?")
            .setMessage("Your opponent wins. The game will be saved.")
            .setNegativeButton("Cancel", null).setPositiveButton("Resign", null).show()
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        fun failed(message: String) {
            dialog.setMessage(message.ifBlank { "Unable to confirm resignation. Check the connection and try again." })
            dialog.setCancelable(true); confirm.isEnabled = true; cancel.isEnabled = true
        }
        confirm.setOnClickListener {
            dialog.setMessage("Saving the result…")
            dialog.setCancelable(false); confirm.isEnabled = false; cancel.isEnabled = false
            try { s.action("resign") { ok -> if (ok) dialog.dismiss() else failed(s.notice) } }
            catch (e: Exception) { failed(e.message.orEmpty()) }
        }
    }
    private fun spinner(values: List<String>, parent: LinearLayout) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        minimumHeight = dp(48); parent.addView(this)
    }
    private fun chooseOpponent(s: TrainingService, defaults: Boolean = false) {
        AlertDialog.Builder(this).setTitle(if (defaults) "Defaults for opponent" else "Start Game · Choose opponent")
            .setItems(arrayOf("Local Computer", "Read19")) { _, index -> newGame(s, if (index == 0) "desktop" else "cloud", defaults) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun newGame(s: TrainingService, opponent: String, defaults: Boolean) {
        val providerName = if (opponent == "desktop") "Local Computer" else "Read19"
        val fields = column()
        text("Board size", fields); val size = spinner(listOf("9 × 9", "13 × 13", "19 × 19"), fields)
        text("Your color", fields); val color = spinner(listOf("Black", "White", "Random"), fields)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        size.setSelection(listOf(9, 13, 19).indexOf(prefs.getInt("$opponent.size", prefs.getInt("size", 9))).coerceAtLeast(0))
        color.setSelection(listOf("B", "W", "random").indexOf(prefs.getString("$opponent.color", prefs.getString("color", "W"))).coerceAtLeast(0))
        text("Zero handicap; existing controller rules and komi are retained.", fields)
        text("Game mode", fields)
        val mode = spinner(listOf("Normal game — KataGo", if (opponent == "desktop") "Teaching game — KaTrain" else "Experimental teaching — score-loss retry"), fields)
        text("Difficulty (detailed engine profile is in Settings)", fields)
        val difficulty = spinner(if (opponent == "desktop") listOf("Full strength", "KaTrain configured rank") else listOf("Full strength only"), fields)
        mode.setSelection(if (prefs.getBoolean("$opponent.teaching", prefs.getBoolean("teaching", false))) 1 else 0)
        difficulty.setSelection(if (opponent == "desktop" && prefs.getString("$opponent.difficulty", prefs.getString("difficulty", "ai:default")) == "ai:rank") 1 else 0)
        val builder = AlertDialog.Builder(this).setTitle(if (defaults) "$providerName defaults" else "Start Game · $providerName").setView(ScrollView(this).apply { addView(fields) }).setNegativeButton("Cancel", null).setPositiveButton(if (defaults) "Save" else "Start", null)
        if (!defaults) builder.setMessage("Remove all stones from the board before starting.")
        val dialog = builder.create()
        dialog.setOnShowListener {
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            fun failed(message: String) {
                dialog.setMessage(message.ifBlank { "Unable to start the game. Check the connection and try again." })
                dialog.setCancelable(true); start.isEnabled = true; cancel.isEnabled = true
            }
            start.setOnClickListener {
                safe {
                    start.isEnabled = false; dialog.setCancelable(false); cancel.isEnabled = false
                    try {
                        val boardSize = listOf(9, 13, 19)[size.selectedItemPosition]
                        val player = listOf("B", "W", "random")[color.selectedItemPosition]
                        val teaching = mode.selectedItemPosition == 1
                        val strength = if (difficulty.selectedItemPosition == 0) "ai:default" else "ai:rank"
                        if (defaults) {
                            prefs.edit().putInt("$opponent.size", boardSize).putString("$opponent.color", player).putBoolean("$opponent.teaching", teaching).putString("$opponent.difficulty", strength).apply()
                            dialog.dismiss()
                            return@safe
                        }
                        dialog.setMessage("Connecting and checking the board…")
                        s.startAgainst(opponent, boardSize, player, teaching, strength) { ok ->
                            if (ok) dialog.dismiss() else failed(s.notice)
                        }
                    } catch (e: Exception) { failed(e.message.orEmpty()) }
                }
            }
        }
        dialog.show()
    }
    private fun thresholds(s: TrainingService) {
        val fields = column()
        if (s.profile == "desktop") {
            val values = s.desktop?.optJSONArray("thresholds") ?: error("Connect desktop first")
            val input = field("KaTrain thresholds: descending, comma separated", fields, value = (0 until values.length()).joinToString(",") { values.get(it).toString() })
            form("Teaching thresholds", fields) { s.desktopCommand("thresholds", JSONObject().put("values", JSONArray(input.text.toString().split(",").map { it.trim().toDouble() }))) }
        } else {
            val input = field("Retry above score loss (points)", fields, value = (s.game?.threshold ?: 3.0).toString())
            form("Experimental teaching", fields) { s.setThreshold(input.text.toString().toDouble()) }
        }
    }
    private fun scoring(s: TrainingService) {
        val fields = column()
        val result = field("Agreed SGF result (B+3.5, W+R, 0 or Void)", fields)
        if (s.profile == "cloud") {
            val game = s.game ?: error("Start or resume a game")
            val dead = mutableSetOf<Int>()
            val scoreText = text("Area score with all stones alive, Black minus White: ${game.score()}", fields)
            val board = GoBoardView(this).apply { size = game.size; expected = game.board; physical = game.board; marked = dead }
            board.onPoint = { p ->
                if (game.board[p] != 0) {
                    val group = game.group(game.board, p)
                    if (p in dead) dead.removeAll(group) else dead.addAll(group)
                    val score = game.score(dead)
                    val proposed = if (score == 0.0) "0" else "${if (score > 0) "B" else "W"}+${kotlin.math.abs(score)}"
                    scoreText.text = "Chinese area score: $proposed"; result.setText(proposed); board.invalidate()
                }
            }
            fields.addView(board, LinearLayout.LayoutParams(-1, dp(280)))
            text("Tap groups to mark or unmark dead stones. Confirm only after agreeing life and death. Sensed stones remain unchanged.", fields)
        } else {
            s.desktopCommand("score")
            text("KataGo estimate (Black): ${s.desktop?.opt("score_lead_black") ?: "unavailable"}. Enter the agreed final result; an estimate is not a final score.", fields)
        }
        form("Confirm scoring / end game", fields) { s.action("finish", result.text.toString().trim()) }
    }
    private fun library(s: TrainingService) {
        text("Autosaved after moves, passes, undo and results. Continue requires physical-board reconciliation.")
        if (s.profile == "desktop") button("Refresh desktop library") { s.desktopCommand("library") }
        val games = s.store.all()
        libraryVersion = games.joinToString { "${it.optString("id")}:${it.optLong("saved_at")}" }
        if (games.isEmpty()) text("No saved games yet")
        for (record in games) {
            val result = if (record.isNull("result")) "Unfinished" else record.optString("result", "Unfinished")
            button("${record.optString("profile")} · ${record.optInt("size")}×${record.optInt("size")} · $result\n${timestamp(record.optLong("saved_at"))}") {
                AlertDialog.Builder(this).setTitle("Saved game").setItems(arrayOf("Continue unfinished game", "Review", "Export SGF")) { _, choice -> safe {
                    if (choice == 2) export(record.getString("sgf"))
                    else { s.openSaved(record, choice == 1); page = if (choice == 1) "Analysis" else "Match"; build() }
                } }.show()
            }
        }
    }
    private fun export(sgf: String) {
        exportSgf = sgf
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/x-go-sgf").putExtra(Intent.EXTRA_TITLE, "izis-game.sgf"), 10)
    }
    @Deprecated("Platform document picker supports API 25")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) safe {
            val uri = data?.data ?: error("No destination"); val sgf = exportSgf ?: error("Export content unavailable")
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(sgf.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write destination")
            exportSgf = null; Toast.makeText(this, "SGF exported", Toast.LENGTH_SHORT).show()
        }
    }
    private fun timestamp(ms: Long) = if (ms <= 0) "Never" else DateFormat.getDateTimeInstance().format(Date(ms))
    private fun refresh() {
        val s = service ?: return
        if (page == "Games" && s.store.all().joinToString { "${it.optString("id")}:${it.optLong("saved_at")}" } != libraryVersion) {
            build(); return
        }
        if (page == "Settings") {
            fun describe(name: String, st: ProfileStatus) = "$name — ${st.state.label}\nConfiguration: ${st.configuration}\nNetwork: ${st.network}\nAuthentication: ${st.authentication}\nAnalysis: ${st.analysis}\nLast successful check: ${timestamp(st.lastCheck)}\n${st.message}"
            statusView?.text = describe("Read19 Cloud", s.cloud.status) + "\n\nQuota:\n${s.cloud.status.quota}\nUsage timestamp: ${timestamp(s.cloud.usageTime)}\n\n" + describe("Local computer", s.desktopStatus) + "\n${s.notice}"
            return
        }
        statusView?.text = "${if (s.profile == "cloud") "Read19 Cloud" else "Local computer"} · ${if (s.profile == "cloud") s.cloud.status.state.label else s.desktopStatus.state.label}\n${s.hardwareStatus}\n${s.notice}"
        if (page == "Match") statusView?.text = "${if (s.profile == "cloud") "Read19" else "Computer"} · ${if (if (s.profile == "cloud") s.cloud.connected else s.desktopConnected) "Connected" else "Disconnected"} · ${if (s.hardware.observed != null) "Sensors connected" else "Waiting for sensors"}" + if (s.notice.isNotEmpty()) "\n${s.notice}" else ""
        if (page == "Analysis") statusView?.text = "${if (s.profile == "desktop") "Local Computer" else "Read19"} · ${s.hardwareStatus}\n${s.notice.ifEmpty { if (s.profile == "desktop") s.desktopStatus.state.label else s.cloud.status.state.label }}"
        val board = boardView ?: return
        board.heatmap = page == "Analysis"
        if (s.profile == "cloud") {
            val g = s.game ?: run { detailsView?.text = "Choose Start Game to play."; return }
            board.size = g.size; board.expected = g.board; board.physical = s.sync.observed
            board.policy = s.analysis?.optJSONArray("policy"); board.candidates = s.suggestions ?: (if (page == "Analysis") s.analysis?.optJSONArray("moveInfos") else null)
            detailsView?.text = "Move ${g.moves.size} · ${if (g.next == 1) "Black" else "White"} to play · You: ${if (g.human == 1) "Black" else "White"}\n${g.result ?: if (g.twoPasses) "Two passes — score the game" else "In progress"}\n${s.sync.status}\n${if (s.sync.observed == null) "No fresh physical position" else "Solid stones: physical board; outlines: required position"}\n${g.feedback}\n" +
                (if (g.teaching) "Experimental: score-loss retry; full KaTrain unavailable\n" else "") +
                (s.analysis?.let { "Black score estimate: ${it.optJSONObject("rootInfo")?.opt("scoreLead")}\n${candidateText(it.optJSONArray("moveInfos"))}" } ?: "Analysis not available")
        } else {
            val state = s.desktop ?: run { detailsView?.text = "Choose Start Game to play."; return }
            board.size = state.getJSONArray("board_size").getInt(0)
            fun stones(key: String) = IntArray(board.size * board.size).also { array ->
                val stones = state.optJSONArray(key) ?: return@also
                for (i in 0 until stones.length()) {
                    val stone = stones.getJSONArray(i); val x = stone.getInt(0); val y = stone.getInt(1)
                    if (x in 0 until board.size && y in 0 until board.size) array[y * board.size + x] = if (stone.getString(2) == "B") 1 else 2
                }
            }
            board.expected = stones("stones"); board.physical = if (state.optBoolean("physical_available")) stones("physical") else null
            board.policy = state.optJSONArray("policy"); board.candidates = s.suggestions ?: (if (page == "Analysis") state.optJSONArray("candidate_details") else null)
            detailsView?.text = "Move ${state.optInt("depth")} · ${state.optString("next_player")} to play\n${state.optString("status")}\nController: ${state.optString("controller")}\nResult: ${state.opt("result")}\nLast analysis: ${timestamp((state.optDouble("last_analysis", 0.0) * 1000).toLong())}\nScore loss: ${state.opt("last_move_points_lost")}\nBlack estimate: ${state.opt("score_lead_black")}\n${candidateText(state.optJSONArray("candidate_details"))}"
        }
        if (s.finishedGame) { board.physical = board.expected; board.candidates = null }
        if (page == "Match") detailsView?.text = if (s.finishedGame) "Finished · ${if (s.profile == "cloud") s.game?.result else s.desktop?.optString("result")} — remove stones at leisure" else
            if (s.profile == "cloud") "Move ${s.game?.moves?.size ?: 0} · ${if (s.game?.next == 1) "Black" else "White"} to play" else "Move ${s.desktop?.optInt("depth") ?: 0} · ${s.desktop?.optString("next_player") ?: ""} to play"
        if (s.notice.isNotEmpty()) detailsView?.append("\n${s.notice}")
        if (s.hardwareStatus.contains("unconfirmed") || (!s.hardware.active && !s.hardware.released)) detailsView?.append("\n${s.hardwareStatus}")
        if (!s.finishedGame) s.position.guidance(board.size).takeIf { it.isNotEmpty() }?.let { detailsView?.append("\n$it") }
        val region = (0 until board.size * board.size).map { BoardPosition.fullPoint(it, board.size) }.toSet()
        val outside = s.position.observed?.indices?.filter { it !in region && s.position.observed!![it] != 0 }.orEmpty()
        if (!s.finishedGame && outside.isNotEmpty()) detailsView?.append("\nRemove stones outside the playing region: " + outside.joinToString { "${"ABCDEFGHJKLMNOPQRST"[it % 19]}${it / 19 + 1}" })
        val stones = (board.physical ?: board.expected).toList().mapIndexedNotNull { p, color ->
            if (color == 0) null else "${if (color == 1) "Black" else "White"} ${"ABCDEFGHJKLMNOPQRST"[p % board.size]}${p / board.size + 1}"
        }.joinToString(", ")
        board.contentDescription = "${detailsView?.text}. Stones: $stones"; board.invalidate()
    }
    private fun candidateText(array: JSONArray?): String = if (array == null) "" else (0 until minOf(5, array.length())).joinToString("\n") {
        val c = array.getJSONObject(it); "${it + 1}. ${c.optString("move")} · score ${c.opt("scoreLead")} · variation ${c.optJSONArray("pv") ?: "unavailable"}"
    }
    override fun onStart() { super.onStart(); visible = true; service?.visibility(true) }
    override fun onStop() { visible = false; service?.visibility(false); super.onStop() }
    @Deprecated("API 25 board navigation")
    override fun onBackPressed() {
        AlertDialog.Builder(this).setTitle("Exit Smart Go and release the board?")
            .setNegativeButton("Stay", null).setPositiveButton("Exit") { _, _ -> finish() }.show()
    }
    override fun onDestroy() { service?.changed = null; if (isFinishing) startService(Intent(this, TrainingService::class.java).setAction("stop")); if (bound) unbindService(connection); super.onDestroy() }
}
