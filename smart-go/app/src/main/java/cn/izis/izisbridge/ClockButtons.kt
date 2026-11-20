package cn.izis.izisbridge

/** The MCU reports a completed long press; no down/up timing is exposed by the Service. */
internal class ClockButtons {
    private var last = Long.MIN_VALUE
    fun accept(frame: String, now: Long): Boolean {
        if (frame != "~WTK#" || (last != Long.MIN_VALUE && now - last < 1000)) return false
        last = now
        return true
    }
}
