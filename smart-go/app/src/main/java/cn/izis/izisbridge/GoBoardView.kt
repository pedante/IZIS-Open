package cn.izis.izisbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import kotlin.math.min
import kotlin.math.roundToInt

class GoBoardView(context: Context) : View(context) {
    var size = 19
    var expected = IntArray(361)
    var physical: IntArray? = null
    var policy: JSONArray? = null
    var candidates: JSONArray? = null
    var heatmap = false
    var marked: Set<Int> = emptySet()
    var onPoint: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun spacing() = (min(width, height).toFloat() - 40f) / (size - 1)
    private fun offsetX() = (width - min(width, height)) / 2f
    private fun offsetY() = (height - min(width, height)) / 2f
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(218, 181, 124))
        canvas.save(); canvas.translate(offsetX(), offsetY())
        val step = spacing(); val edge = 20f; val end = edge + step * (size - 1)
        paint.color = Color.DKGRAY; paint.strokeWidth = 1f; paint.style = Paint.Style.STROKE
        for (i in 0 until size) {
            canvas.drawLine(edge, edge + step * i, end, edge + step * i, paint)
            canvas.drawLine(edge + step * i, edge, edge + step * i, end, paint)
        }
        val data = policy
        val peak = if (data != null && data.length() == size * size + 1) (0 until size * size).maxOf { data.optDouble(it, 0.0) }.coerceAtLeast(0.0001) else 1.0
        for (p in 0 until size * size) {
            val x = edge + p % size * step; val y = edge + (size - 1 - p / size) * step
            if (heatmap && data != null) {
                val value = data.optDouble((size - 1 - p / size) * size + p % size, 0.0)
                if (value.isFinite() && peak.isFinite() && value > 0) {
                    paint.style = Paint.Style.FILL; paint.color = Color.argb((180 * value / peak).toInt().coerceIn(0, 180), 230, 45, 25)
                    canvas.drawRect(x - step / 2, y - step / 2, x + step / 2, y + step / 2, paint)
                }
            }
            val wanted = expected.getOrElse(p) { 0 }
            val actual = physical?.getOrNull(p) ?: wanted
            if (actual != 0 || wanted != 0) {
                paint.color = if ((if (actual != 0) actual else wanted) == 1) Color.BLACK else Color.WHITE
                paint.style = if (actual != 0) Paint.Style.FILL else Paint.Style.STROKE; paint.strokeWidth = 3f
                canvas.drawCircle(x, y, step * 0.43f, paint)
                if (actual != wanted && physical != null) {
                    paint.color = Color.rgb(180, 0, 0); paint.style = Paint.Style.STROKE; canvas.drawCircle(x, y, step * 0.48f, paint)
                }
            }
            if (p in marked) { paint.color = Color.RED; paint.strokeWidth = 3f; canvas.drawLine(x - step / 3, y - step / 3, x + step / 3, y + step / 3, paint) }
        }
        candidates?.let { moves ->
            for (i in 0 until min(5, moves.length())) {
                val move = moves.optJSONObject(i)?.optString("move") ?: continue
                val x = "ABCDEFGHJKLMNOPQRST".indexOf(move.firstOrNull() ?: ' '); val y = (move.drop(1).toIntOrNull() ?: 0) - 1
                if (x !in 0 until size || y !in 0 until size) continue
                if (expected.getOrElse(y * size + x) { 0 } != 0 || (physical?.getOrNull(y * size + x) ?: 0) != 0) continue
                val cx = edge + x * step; val cy = edge + (size - 1 - y) * step
                paint.style = Paint.Style.FILL; paint.color = Color.rgb(0, 95, 160); canvas.drawCircle(cx, cy, step * 0.35f, paint)
                paint.color = Color.WHITE; paint.textSize = step * 0.5f; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("${i + 1}", cx, cy + step * 0.17f, paint)
            }
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPoint == null) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) {
            val x = ((event.x - offsetX() - 20) / spacing()).roundToInt(); val y = size - 1 - ((event.y - offsetY() - 20) / spacing()).roundToInt()
            if (x in 0 until size && y in 0 until size) onPoint?.invoke(y * size + x)
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
