package cn.izis.izisbridge

/** A and P stay separate; Q is only a candidate until the selected controller accepts it. */
class PhysicalPosition {
    var accepted = IntArray(361)
        private set
    var observed: IntArray? = null
        private set
    private var identity = ""
    private var changed = 0L
    private var received = 0L
    private var armed = false
    private val correctionPoints = mutableSetOf<Int>()
    fun reset() { observed = null; armed = false }
    fun accept(board: IntArray, revision: String) {
        require(board.size == 361 && board.all { it in 0..2 })
        if (identity != revision || !accepted.contentEquals(board)) {
            accepted = board.copyOf(); identity = revision; armed = false
            correctionPoints.clear()
            observed?.let { physical ->
                correctionPoints.addAll(board.indices.filter { board[it] != 0 && physical[it] != board[it] })
            }
        }
    }
    fun observe(board: IntArray?, now: Long) {
        if (board == null || board.size != 361 || board.any { it !in 0..2 }) { reset(); return }
        if (observed?.contentEquals(board) != true) changed = now
        observed = board.copyOf(); received = now
        correctionPoints.addAll(accepted.indices.filter { accepted[it] != 0 && board[it] != accepted[it] })
        if (stable(now)) correctionPoints.removeAll { board[it] == accepted[it] }
        if (ready(now)) armed = true
    }
    fun stable(now: Long) = observed != null && now - received <= 30_000 && now - changed >= 600
    fun ready(now: Long) = stable(now) && observed!!.contentEquals(accepted)
    fun corrections(size: Int): Map<Int, Int> {
        val physical = observed ?: return emptyMap()
        return (0 until size * size).mapNotNull { p ->
            val full = BoardPosition.fullPoint(p, size)
            if (accepted[full] != 0 && (full in correctionPoints || physical[full] != accepted[full])) p to accepted[full] else null
        }.toMap()
    }
    fun differs() = correctionPoints.isNotEmpty() || observed?.contentEquals(accepted) == false
    fun guidance(size: Int): String {
        val physical = observed ?: return "No fresh physical position"
        return (0 until size * size).mapNotNull { p ->
            val full = BoardPosition.fullPoint(p, size)
            val expected = accepted[full]
            val actual = physical[full]
            val point = "${"ABCDEFGHJKLMNOPQRST"[p % size]}${p / size + 1}"
            val color = if (expected == 1) "black" else "white"
            when {
                expected == actual -> null
                expected == 0 -> "Remove extra stone at $point"
                actual == 0 -> "Place or restore $color at $point"
                else -> "Replace stone at $point with $color"
            }
        }.joinToString("\n")
    }
    fun proposal(color: Int, size: Int, now: Long): Int? {
        if (!armed || !stable(now)) return null
        val physical = observed ?: return null
        // A displaced accepted stone is a correction, never a new placement or inferred capture.
        if (physical.indices.any { accepted[it] != 0 && physical[it] != accepted[it] }) return null
        val added = physical.indices.filter { physical[it] != 0 && physical[it] != accepted[it] }
        if (added.size != 1) return null
        val full = added.single()
        if (physical[full] != color || accepted[full] != 0) return null
        return (0 until size * size).firstOrNull { BoardPosition.fullPoint(it, size) == full }
    }
    fun submitted() { armed = false }
}
