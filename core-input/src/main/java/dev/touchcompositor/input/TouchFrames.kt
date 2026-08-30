package dev.touchcompositor.input

/** Origin is kept separate so physical and synthetic pointers can coexist safely. */
enum class PointerOrigin { PHYSICAL, SYNTHETIC, PLAYBACK }

enum class TouchAction {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
}

/** Coordinates and contact dimensions are display pixels. */
data class PointerState(
    val id: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val size: Float = 0.05f,
    val touchMajor: Float = 5f,
    val touchMinor: Float = 5f,
    val toolMajor: Float = touchMajor,
    val toolMinor: Float = touchMinor,
    val origin: PointerOrigin = PointerOrigin.SYNTHETIC,
)

/**
 * One logical Android touch frame. For POINTER_DOWN/POINTER_UP, pointers contains
 * every pointer participating in the gesture, including changedPointerId.
 */
data class ComposedFrame(
    val action: TouchAction,
    val pointers: List<PointerState>,
    val changedPointerId: Int,
    val eventTimeNanos: Long,
) {
    init {
        require(pointers.map { it.id }.distinct().size == pointers.size) { "duplicate pointer id" }
        require(pointers.all { it.id in 0..31 }) { "Android pointer ids must be in 0..31" }
        when (action) {
            TouchAction.DOWN, TouchAction.POINTER_DOWN,
            TouchAction.POINTER_UP, TouchAction.UP ->
                require(pointers.any { it.id == changedPointerId }) { "changed pointer missing from frame" }
            TouchAction.MOVE, TouchAction.CANCEL -> Unit
        }
    }
}

fun interface TouchFrameSink {
    /** Returns false when the backend rejected the frame. */
    fun emit(frame: ComposedFrame): Boolean
}
