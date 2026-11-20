package cn.izis.izisbridge

import java.util.Locale

/** Only acknowledged batches enter the displayed pattern. Changes coalesce while a write is pending. */
internal class LedController(private val send: (String) -> Unit, private val report: (String) -> Unit) {
    companion object {
        const val GREEN = "r000g008b000"
        const val RED = "r008g000b000"
        const val WHITE = "r008g008b008"
        const val OFF = "r000g000b000"
        private val single = Regex("~SHP([0-9]{3}),(?:$GREEN|$WHITE|$OFF),1#")
        private const val PAD = "001r000g000b000"
        private val slot = Regex("([A-D])([0-9]{3})(r[0-9]{3}g[0-9]{3}b[0-9]{3})")
        @JvmStatic fun allowed(command: String): Boolean {
            if (command == "~RGC#" || command == "~STA#") return true
            single.matchEntire(command)?.let { return it.groupValues[1].toInt() in 1..361 }
            if (command.length != 71 || !command.startsWith("~HOT") || !command.endsWith("#")) return false
            val count = command[69].digitToIntOrNull() ?: return false
            if (count !in 1..4) return false
            val points = linkedMapOf<Int, String>()
            for (m in slot.findAll(command).take(count)) {
                val point = m.groupValues[2].toInt()
                val rgb = m.groupValues[3]
                if (point !in 1..361 || rgb !in listOf(GREEN, WHITE, RED)) return false
                points[point] = rgb
            }
            return points.size == count && batch(points) == command
        }
        fun batch(points: Map<Int, String>): String {
            require(points.size in 1..4)
            return buildString {
                append("~HOT")
                val entries = points.entries.toList()
                for (i in 0..3) {
                    append("ABCD"[i])
                    val entry = entries.getOrNull(i)
                    if (entry == null) append(PAD) else {
                        require(entry.key in 1..361 && entry.value in listOf(GREEN, RED, WHITE))
                        append(String.format(Locale.ROOT, "%03d", entry.key)).append(entry.value)
                    }
                }
                append("N").append(points.size).append("#")
            }
        }
    }
    private var wanted = emptyMap<Int, String>()
    private val shown = mutableMapOf<Int, String>()
    private var pending: Map<Int, String>? = null
    private var expected = ""
    private var move: Pair<Int, String>? = null
    private var shownMove: Pair<Int, String>? = null
    private var deadline = 0L
    private var unknown = false
    private var failed = false
    private var clearAttempts = 0
    fun display(pattern: Map<Int, String>, indicator: Pair<Int, String>? = null) {
        wanted = pattern
        move = indicator
    }
    fun clean() = !unknown && !failed && shown.isEmpty() && shownMove == null && pending == null
    fun lost(reason: String) {
        failed = true; pending = null; unknown = true
        report("LED cleanup unconfirmed — $reason")
    }
    fun cleanup() {
        display(emptyMap())
        pending = null
        failed = false
        unknown = true
        clearAttempts = 2
    }
    fun receive(frame: String) {
        val sent = pending ?: return
        if (frame != expected) return
        when (frame) {
            "~ALC#" -> { shown.clear(); shownMove = null; unknown = false; clearAttempts = 0 }
            "~HSM#" -> shown.putAll(sent)
            "~HCS#" -> {
                val (point, rgb) = sent.entries.single()
                if (rgb == OFF && shown[point] == RED) shown.remove(point)
                shownMove = if (rgb == OFF) null else point to rgb
            }
        }
        pending = null
        report("ack=$frame")
    }
    fun tick(now: Long, board: IntArray? = null) {
        if (failed) return
        if (pending != null) {
            if (now < deadline) return
            if (clearAttempts == 0) { lost("missing $expected"); return }
            report("retry missing $expected")
        }
        val next: Map<Int, String>
        val command: String
        if (unknown || shown.any { (point, rgb) -> rgb != RED && point !in wanted }) {
            next = emptyMap()
            expected = "~ALC#"
            command = "~RGC#"
            if (clearAttempts > 0) clearAttempts--
        } else {
            val removedSuggestion = shown.entries.firstOrNull { (point, rgb) -> rgb == RED && point !in wanted }
            val additions = wanted.filter { (p, rgb) -> shown[p] != rgb }.entries.take(4).associate { it.toPair() }
            if (removedSuggestion == null && additions.isNotEmpty()) {
                next = additions
                expected = "~HSM#"
                command = batch(next)
            } else {
                // A targeted OFF also clears the previous SHP light. Restore any still-needed move after the suggestions.
                val (point, rgb) = if (removedSuggestion != null) removedSuggestion.key to OFF else {
                    val visibleMove = move?.takeIf { board == null || board[361 - it.first] == 0 }
                    if (visibleMove == shownMove) return
                    shownMove?.let { it.first to OFF } ?: visibleMove ?: return
                }
                next = mapOf(point to rgb)
                expected = "~HCS#"
                command = String.format(Locale.ROOT, "~SHP%03d,%s,1#", point, rgb)
            }
        }
        pending = next
        deadline = now + 3000
        send(command)
    }
}
