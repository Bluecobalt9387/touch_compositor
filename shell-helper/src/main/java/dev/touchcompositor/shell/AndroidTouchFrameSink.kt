package dev.touchcompositor.shell

import android.os.SystemClock
import android.view.MotionEvent
import dev.touchcompositor.input.ComposedFrame
import dev.touchcompositor.input.TouchAction
import dev.touchcompositor.input.TouchFrameSink
import kotlin.math.max

/** Converts backend-independent composed frames to Android MotionEvent injection. */
class AndroidTouchFrameSink(
    private val injector: InputInjectorCompat = InputInjectorCompat(),
    private val deviceId: Int = 6,
) : TouchFrameSink {
    private var downTimeMillis = 0L

    @Synchronized
    override fun emit(frame: ComposedFrame): Boolean {
        if (frame.pointers.isEmpty()) return frame.action == TouchAction.CANCEL
        val now = SystemClock.uptimeMillis()
        if (frame.action == TouchAction.DOWN || downTimeMillis == 0L) downTimeMillis = now

        val props = Array(frame.pointers.size) { MotionEvent.PointerProperties() }
        val coords = Array(frame.pointers.size) { MotionEvent.PointerCoords() }
        frame.pointers.forEachIndexed { i, p ->
            props[i].id = p.id
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = p.x
            coords[i].y = p.y
            coords[i].pressure = p.pressure.coerceAtLeast(0f)
            coords[i].size = p.size.coerceAtLeast(0f)
            coords[i].touchMajor = max(0f, p.touchMajor)
            coords[i].touchMinor = max(0f, p.touchMinor)
            coords[i].toolMajor = max(0f, p.toolMajor)
            coords[i].toolMinor = max(0f, p.toolMinor)
        }

        val action = when (frame.action) {
            TouchAction.DOWN -> MotionEvent.ACTION_DOWN
            TouchAction.MOVE -> MotionEvent.ACTION_MOVE
            TouchAction.UP -> MotionEvent.ACTION_UP
            TouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
            TouchAction.POINTER_DOWN, TouchAction.POINTER_UP -> {
                val index = frame.pointers.indexOfFirst { it.id == frame.changedPointerId }
                require(index >= 0) { "changed pointer not present" }
                val base = if (frame.action == TouchAction.POINTER_DOWN) {
                    MotionEvent.ACTION_POINTER_DOWN
                } else {
                    MotionEvent.ACTION_POINTER_UP
                }
                base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }

        val event = MotionEvent.obtain(
            downTimeMillis,
            now,
            action,
            frame.pointers.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            deviceId,
            0,
            0x1002,
            0,
        )
        return try {
            injector.inject(event, 1)
        } finally {
            event.recycle()
            if (frame.action == TouchAction.UP || frame.action == TouchAction.CANCEL) downTimeMillis = 0L
        }
    }
}
