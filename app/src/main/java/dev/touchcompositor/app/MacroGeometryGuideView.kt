package dev.touchcompositor.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Non-interactive guide drawn beneath draggable macro position handles. */
class MacroGeometryGuideView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 52, 103, 215)
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 52, 103, 215)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    var arrowEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var x1: Float = 0f
        set(value) { field = value; invalidate() }
    var y1: Float = 0f
        set(value) { field = value; invalidate() }
    var x2: Float = 0f
        set(value) { field = value; invalidate() }
    var y2: Float = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!arrowEnabled) {
            val r = 10f * density
            canvas.drawCircle(x1, y1, r, haloPaint)
            canvas.drawCircle(x1, y1, 3f * density, haloPaint)
            canvas.drawLine(x1 - r * 1.25f, y1, x1 + r * 1.25f, y1, haloPaint)
            canvas.drawLine(x1, y1 - r * 1.25f, x1, y1 + r * 1.25f, haloPaint)
            return
        }
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        val angle = atan2(y2 - y1, x2 - x1)
        val len = 18f * density
        val wing = 0.60f
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(x2 - len * cos(angle - wing), y2 - len * sin(angle - wing))
            moveTo(x2, y2)
            lineTo(x2 - len * cos(angle + wing), y2 - len * sin(angle + wing))
        }
        canvas.drawPath(path, linePaint)
    }
}
