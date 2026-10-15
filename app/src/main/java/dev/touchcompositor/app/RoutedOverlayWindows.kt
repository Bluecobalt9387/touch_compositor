package dev.touchcompositor.app

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.pow

/** Windows render only. Physical UI gestures arrive through the runtime's separate route. */
class RoutedOverlayWindows(
    private val wm: WindowManager,
    private val geometry: LogicalScreenGeometry,
    private val main: Handler,
    private val send: (String, (Boolean) -> Unit) -> Unit,
) : AutoCloseable {
    private data class Window(
        val id: String, val view: View, val params: WindowManager.LayoutParams,
        val interactive: Boolean,
        var downTime: Long = 0, var x: Float = 0f, var y: Float = 0f,
    )
    private val windows = linkedMapOf<View, Window>()
    private var sequence = 0L
    private var revision = 0L
    private var lastCommand: String? = null
    private var closed = false
    private val publish = Runnable { publish() }

    fun addView(view: View, params: WindowManager.LayoutParams) {
        val interactive = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        val window = Window("ui_${++sequence}", view, params, interactive)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.alpha = 0f // Visible only after its routing map is acknowledged.
        windows[view] = window
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }
        wm.addView(view, params)
        schedule()
    }

    fun updateViewLayout(view: View, params: WindowManager.LayoutParams) {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(view, params)
        schedule()
    }

    fun removeView(view: View) {
        windows.remove(view)?.let { cancel(it) }
        wm.removeView(view)
        schedule()
    }

    fun suspendRouting() {
        ++revision
        lastCommand = null
        windows.values.toList().forEach { w ->
            cancel(w)
            w.params.alpha = 0f
            w.params.flags = w.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(w.view, w.params)
        }
    }

    fun sync() { lastCommand = null; schedule() }
    private fun schedule() {
        if (closed) return
        main.removeCallbacks(publish)
        main.post(publish)
    }

    private fun publish() {
        if (closed) return
        val measured = windows.values.mapNotNull { w -> geometry.globalBounds(w.view)?.let { w to it } }
        val command = buildString {
            val targets = measured.filter { it.first.interactive }
            append("UI_SET ").append(targets.size)
            targets.forEach { (w, b) -> append(" ${w.id} ${b.left} ${b.top} ${b.right} ${b.bottom}") }
        }
        if (lastCommand == command) return
        lastCommand = command
        val rev = ++revision
        send(command) { ok ->
            if (closed || rev != revision) return@send
            if (!ok) { lastCommand = null; main.postDelayed(publish, 500); return@send }
            measured.forEach { (w, b) ->
                if (windows[w.view] !== w) return@forEach
                // Android blocks touches through overlapping opaque overlays. Bound the
                // combined opacity, not just each individual window's alpha.
                val overlaps = measured.count { (_, other) ->
                    b.left < other.right && other.left < b.right && b.top < other.bottom && other.top < b.bottom
                }.coerceAtLeast(1)
                val alpha = (1.0 - 0.25.pow(1.0 / overlaps)).toFloat()
                w.params.alpha = alpha
                w.params.flags = w.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(w.view, w.params)
            }
        }
    }

    fun onTouch(parts: List<String>) {
        if (closed || parts.size != 5) return
        val w = windows.values.firstOrNull { it.id == parts[1] } ?: return
        val x = parts[3].toFloatOrNull() ?: return
        val y = parts[4].toFloatOrNull() ?: return
        val action = when (parts[2]) {
            "DOWN" -> MotionEvent.ACTION_DOWN
            "MOVE" -> MotionEvent.ACTION_MOVE
            "UP" -> MotionEvent.ACTION_UP
            "CANCEL" -> MotionEvent.ACTION_CANCEL
            else -> return
        }
        if (action == MotionEvent.ACTION_DOWN) w.downTime = SystemClock.uptimeMillis()
        if (w.downTime == 0L) return
        w.x = x; w.y = y
        val downTime = w.downTime
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) w.downTime = 0
        dispatch(w, action, x, y, downTime)
    }

    private fun dispatch(w: Window, action: Int, x: Float, y: Float, downTime: Long = w.downTime) {
        val origin = geometry.globalOrigin(w.view) ?: return
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        // offsetLocation changes local coordinates while preserving raw screen coordinates.
        event.offsetLocation(-origin.x, -origin.y)
        try { w.view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun cancel(w: Window) {
        if (w.downTime != 0L) { dispatch(w, MotionEvent.ACTION_CANCEL, w.x, w.y); w.downTime = 0 }
    }

    override fun close() {
        closed = true
        main.removeCallbacks(publish)
        windows.values.toList().forEach(::cancel)
    }
}
