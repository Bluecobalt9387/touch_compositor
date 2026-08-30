package dev.touchcompositor.input

/**
 * Owns virtual pointer identities. Android MotionEvent generation lives in the
 * shell helper; this module stays easy to unit test.
 */
class TouchCompositor(private val maxPointers: Int = 20) {
    private val active = linkedMapOf<Int, TouchPoint>()

    fun update(point: TouchPoint): List<TouchPoint> {
        require(point.id in 0 until maxPointers)
        when (point.phase) {
            TouchPhase.DOWN, TouchPhase.MOVE -> active[point.id] = point
            TouchPhase.UP -> active.remove(point.id)
        }
        return active.values.toList()
    }

    fun snapshot(): List<TouchPoint> = active.values.toList()
    fun reset() = active.clear()
}
