package dev.touchcompositor.shell

import dev.touchcompositor.input.ComposedFrame
import dev.touchcompositor.input.PlaybackOptions
import dev.touchcompositor.input.PlaybackResult
import dev.touchcompositor.input.PlaybackTarget
import dev.touchcompositor.input.PointerIdAllocator
import dev.touchcompositor.input.PointerOrigin
import dev.touchcompositor.input.PointerState
import dev.touchcompositor.input.TouchAction
import dev.touchcompositor.input.TouchAttributes
import dev.touchcompositor.input.TouchPlayer
import dev.touchcompositor.input.TouchRecording

/** Macro-only input stream. It never joins the grabbed physical-touch gesture stream. */
class IsolatedMacroTouchSession(deviceId: Int = -1) : AutoCloseable {
    private data class ActivePointer(val externalId: Int, val androidId: Int, var state: PointerState)

    private val lock = Any()
    private val allocator = PointerIdAllocator(32)
    private val active = linkedMapOf<Int, ActivePointer>()
    private val sink = AndroidTouchFrameSink(deviceId = deviceId)
    private val player = TouchPlayer()

    fun down(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) = synchronized(lock) {
        require(pointerId >= 0)
        check(pointerId !in active) { "macro pointer $pointerId already down" }
        val androidId = allocator.acquire(PointerOrigin.SYNTHETIC, pointerId)
        active[pointerId] = ActivePointer(pointerId, androidId, attributes.toState(androidId, x, y))
        val points = states()
        emit(if (points.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN, androidId, points)
    }

    fun move(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) = synchronized(lock) {
        val pointer = active[pointerId] ?: error("macro pointer $pointerId is not down")
        pointer.state = attributes.toState(pointer.androidId, x, y)
        emit(TouchAction.MOVE, pointer.androidId, states())
    }

    fun up(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) = synchronized(lock) {
        val pointer = active[pointerId] ?: error("macro pointer $pointerId is not down")
        pointer.state = attributes.toState(pointer.androidId, x, y)
        val points = states()
        emit(if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP, pointer.androidId, points)
        active.remove(pointerId)
        allocator.release(PointerOrigin.SYNTHETIC, pointerId)
    }

    fun cancelPointers() = synchronized(lock) {
        if (active.isNotEmpty()) {
            val points = states()
            runCatching { sink.emit(ComposedFrame(TouchAction.CANCEL, points, points.first().id, System.nanoTime())) }
        }
        active.clear()
        allocator.reset()
    }

    fun play(recording: TouchRecording, target: PlaybackTarget, options: PlaybackOptions): PlaybackResult {
        synchronized(lock) { check(active.isEmpty()) { "release macro pointers before playback" } }
        return player.play(recording, target, sink, options, PointerOrigin.PLAYBACK)
    }

    fun stopPlayback() = player.stop()

    override fun close() {
        stopPlayback()
        cancelPointers()
    }

    private fun states(): List<PointerState> = active.values.map { it.state }.sortedBy { it.id }

    private fun emit(action: TouchAction, changedPointerId: Int, points: List<PointerState>) {
        check(sink.emit(ComposedFrame(action, points, changedPointerId, System.nanoTime()))) {
            "Android rejected macro frame $action"
        }
    }

    private fun TouchAttributes.toState(androidId: Int, x: Float, y: Float) = PointerState(
        id = androidId, x = x, y = y, pressure = pressure, size = size,
        touchMajor = touchMajor, touchMinor = touchMinor, toolMajor = toolMajor, toolMinor = toolMinor,
        origin = PointerOrigin.SYNTHETIC,
    )
}
