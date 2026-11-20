package cn.izis.izisbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GoMove(val color: Int, val point: Int?)

/** Chinese area rules, no suicide, positional superko. Point 0 is the lower left. */
class GoGame(val size: Int, val human: Int = 1, val teaching: Boolean = false, val komi: Double = 7.5) {
    var id = UUID.randomUUID().toString().replace("-", "")
    val moves = mutableListOf<GoMove>()
    private val positions = mutableListOf(IntArray(size * size))
    val board: IntArray get() = positions.last().copyOf()
    val next: Int get() = if (moves.isEmpty()) 1 else 3 - moves.last().color
    var result: String? = null
    var threshold = 3.0
    var feedback = ""
    val twoPasses: Boolean get() = moves.size >= 2 && moves.takeLast(2).all { it.point == null }
    init { require(size in listOf(9, 13, 19) && human in 1..2 && komi.isFinite()) }

    fun neighbors(point: Int): List<Int> = listOfNotNull(
        (point - 1).takeIf { point % size > 0 }, (point + 1).takeIf { point % size < size - 1 },
        (point - size).takeIf { point >= size }, (point + size).takeIf { point < size * (size - 1) })

    fun group(position: IntArray, start: Int): Set<Int> {
        val found = mutableSetOf(start)
        val queue = java.util.ArrayDeque<Int>().apply { add(start) }
        while (queue.isNotEmpty()) for (p in neighbors(queue.removeFirst())) {
            if (position[p] == position[start] && found.add(p)) queue.add(p)
        }
        return found
    }
    fun after(point: Int?, color: Int = next): IntArray {
        require(color == next) { "Wrong turn" }
        val position = board
        if (point == null) return position
        require(point in position.indices && position[point] == 0) { "Occupied or invalid point" }
        position[point] = color
        for (p in neighbors(point)) if (position[p] == 3 - color) {
            val group = group(position, p)
            if (group.none { s -> neighbors(s).any { position[it] == 0 } }) group.forEach { position[it] = 0 }
        }
        require(group(position, point).any { s -> neighbors(s).any { position[it] == 0 } }) { "Suicide is not allowed" }
        require(positions.none { it.contentEquals(position) }) { "Superko: this position has already occurred" }
        return position
    }
    fun play(point: Int?, color: Int = next) {
        check(result == null && !twoPasses) { "Game ended; undo or score" }
        val position = after(point, color)
        moves.add(GoMove(color, point)); positions.add(position)
    }
    fun undo(count: Int = 1) {
        repeat(count.coerceAtMost(moves.size)) { moves.removeAt(moves.lastIndex); positions.removeAt(positions.lastIndex) }
        result = null
    }
    fun score(dead: Set<Int> = emptySet()): Double {
        val position = board
        dead.forEach { require(it in position.indices); position[it] = 0 }
        var black = position.count { it == 1 }
        var white = position.count { it == 2 }
        val visited = mutableSetOf<Int>()
        for (p in position.indices) if (position[p] == 0 && p !in visited) {
            val empty = group(position, p); visited.addAll(empty)
            val borders = empty.flatMap { neighbors(it) }.map { position[it] }.filter { it != 0 }.toSet()
            if (borders == setOf(1)) black += empty.size
            if (borders == setOf(2)) white += empty.size
        }
        return black - white - komi
    }
    fun gtp(point: Int?): String = if (point == null) "pass" else "${"ABCDEFGHJKLMNOPQRST"[point % size]}${point / size + 1}"
    fun point(gtp: String): Int? {
        if (gtp.equals("pass", true)) return null
        require(gtp.length in 2..3) { "Invalid engine move" }
        val x = "ABCDEFGHJKLMNOPQRST".indexOf(gtp[0].uppercaseChar())
        val y = (gtp.substring(1).toIntOrNull() ?: 0) - 1
        require(x in 0 until size && y in 0 until size) { "Invalid engine move" }
        return y * size + x
    }
    fun query(visits: Int = 40): JSONObject = JSONObject().put("boardXSize", size).put("boardYSize", size)
        .put("rules", "chinese-ogs").put("komi", komi).put("maxVisits", visits).put("includePolicy", true)
        .put("includeOwnership", true).put("initialStones", JSONArray())
        .put("moves", JSONArray(moves.map { listOf(if (it.color == 1) "B" else "W", gtp(it.point)) }))

    fun sgf(): String {
        fun escape(s: String) = s.replace("\\", "\\\\").replace("]", "\\]").replace("\r", "")
        val out = StringBuilder("(;GM[1]FF[4]CA[UTF-8]AP[Izis:2]SZ[$size]KM[$komi]RU[Chinese]HA[0]")
        out.append("C[Chinese area scoring with positional superko and no suicide.${if (moves.isEmpty()) " " + escape(feedback) else ""}]")
        out.append("PB[${if (human == 1) "Human" else "Read19 KataGo"}]PW[${if (human == 2) "Human" else "Read19 KataGo"}]")
        result?.let { out.append("RE[${escape(it)}]") }
        for (move in moves) {
            out.append(";${if (move.color == 1) "B" else "W"}[")
            move.point?.let { out.append('a' + it % size).append('a' + size - 1 - it / size) }
            out.append("]")
        }
        if (feedback.isNotEmpty() && moves.isNotEmpty()) out.append("C[${escape(feedback)}]")
        return out.append(")").toString()
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("size", size).put("human", human).put("teaching", teaching)
        .put("komi", komi).put("moves", JSONArray(moves.map { listOf(it.color, it.point) }))
        .put("result", result ?: JSONObject.NULL).put("threshold", threshold).put("feedback", feedback).put("sgf", sgf())

    companion object {
        fun restore(value: JSONObject): GoGame {
            val game = GoGame(value.getInt("size"), value.getInt("human"), value.getBoolean("teaching"), value.getDouble("komi"))
            val id = value.getString("id")
            require(id.matches(Regex("[0-9a-f]{32}")))
            game.id = id
            val moves = value.getJSONArray("moves")
            for (i in 0 until moves.length()) {
                val m = moves.getJSONArray(i)
                game.play(if (m.isNull(1)) null else m.getInt(1), m.getInt(0))
            }
            game.result = if (value.isNull("result")) null else value.getString("result")
            game.threshold = value.optDouble("threshold", 3.0).also { require(it.isFinite() && it in 0.0..100.0) }
            game.feedback = value.optString("feedback")
            return game
        }
    }
}

/** The same stable-snapshot handshake as the Python adapter; lifting alone never means undo. */
class PhysicalSync {
    var observed: IntArray? = null
        private set
    var ready = false
        private set
    var status = "Board unavailable"
        private set
    private var changed = 0L
    private var received = 0L
    private var armed = false
    private var target: IntArray? = null
    fun reset() { observed = null; target = null; ready = false; armed = false; status = "Board unavailable" }
    fun update(game: GoGame, physical: IntArray?, now: Long, accept: Boolean = true): Boolean {
        val position = game.board
        if (target?.contentEquals(position) != true) { target = position; armed = false; ready = false }
        if (physical == null || physical.size != position.size) { reset(); return false }
        received = now
        if (observed?.contentEquals(physical) != true) { observed = physical.copyOf(); changed = now }
        val stable = now - changed >= 350
        ready = stable && physical.contentEquals(position)
        if (ready) { armed = true; status = "Synchronized"; return false }
        status = "Correct the physical board to match the screen"
        if (stable && armed && accept && game.next == game.human && game.result == null && !game.twoPasses) {
            armed = false
            val additions = physical.indices.filter { physical[it] != 0 && physical[it] != position[it] }
            val removed = physical.indices.filter { position[it] != 0 && physical[it] != position[it] }
            if (additions.size == 1 && physical[additions[0]] == game.next) {
                try {
                    val after = game.after(additions[0])
                    require(removed.all { after[it] == 0 })
                    game.play(additions[0]); target = null
                    return true
                } catch (_: IllegalArgumentException) { status = "Illegal move; restore the previous position" }
            }
        }
        return false
    }
    fun fresh(now: Long): Boolean {
        if (now - received > 1500) { ready = false; armed = false; status = "Board unavailable: stale snapshot" }
        return ready
    }
}
