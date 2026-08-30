package dev.touchcompositor.input

import java.util.concurrent.locks.LockSupport

interface PlaybackClock {
    fun nowNanos(): Long
    fun sleepNanos(nanos: Long)
}

object SystemPlaybackClock : PlaybackClock {
    override fun nowNanos(): Long = System.nanoTime()
    override fun sleepNanos(nanos: Long) {
        if (nanos > 0) LockSupport.parkNanos(nanos)
    }
}

data class PlaybackTarget(
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int = 0,
) {
    init {
        require(displayWidth > 0 && displayHeight > 0)
        require(rotation in setOf(0, 90, 180, 270))
    }
}

data class PlaybackOptions(
    val speed: Double = 1.0,
    val repeatCount: Int = 1,
    /** Add an offset when recorded pointer ids must live beside physical ids. */
    val pointerIdOffset: Int = 0,
) {
    init {
        require(speed > 0.0)
        require(repeatCount >= 1)
        require(pointerIdOffset in 0..31)
    }
}

enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPING }

data class PlaybackResult(val completedRepeats: Int, val stopped: Boolean, val emittedFrames: Long)

/** Blocking player; call play() from a worker thread and control it from any thread. */
class TouchPlayer(private val clock: PlaybackClock = SystemPlaybackClock) {
    @Volatile var state: PlaybackState = PlaybackState.IDLE
        private set

    fun pause() {
        if (state == PlaybackState.PLAYING) state = PlaybackState.PAUSED
    }

    fun resume() {
        if (state == PlaybackState.PAUSED) state = PlaybackState.PLAYING
    }

    fun stop() {
        if (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED) state = PlaybackState.STOPPING
    }

    fun play(
        recording: TouchRecording,
        target: PlaybackTarget,
        sink: TouchFrameSink,
        options: PlaybackOptions = PlaybackOptions(),
        origin: PointerOrigin = PointerOrigin.PLAYBACK,
    ): PlaybackResult {
        check(state == PlaybackState.IDLE) { "player already active" }
        state = PlaybackState.PLAYING
        var repeatsDone = 0
        var emitted = 0L
        var stopped = false
        try {
            repeat(options.repeatCount) {
                if (state == PlaybackState.STOPPING) {
                    stopped = true
                    return@repeat
                }
                val start = clock.nowNanos()
                var pausedNanos = 0L
                for (frame in recording.frames) {
                    while (state == PlaybackState.PAUSED) {
                        val p0 = clock.nowNanos()
                        clock.sleepNanos(1_000_000L)
                        pausedNanos += clock.nowNanos() - p0
                    }
                    if (state == PlaybackState.STOPPING) {
                        stopped = true
                        break
                    }
                    val due = start + pausedNanos + (frame.offsetNanos / options.speed).toLong()
                    while (true) {
                        val remaining = due - clock.nowNanos()
                        if (remaining <= 0L) break
                        clock.sleepNanos(minOf(remaining, 2_000_000L))
                        if (state == PlaybackState.STOPPING) {
                            stopped = true
                            break
                        }
                    }
                    if (stopped) break
                    if (!sink.emit(frame.toComposedFrame(recording.metadata, target, clock.nowNanos(), origin, options.pointerIdOffset))) {
                        stopped = true
                        break
                    }
                    emitted++
                }
                if (!stopped) repeatsDone++
            }
        } finally {
            state = PlaybackState.IDLE
        }
        return PlaybackResult(repeatsDone, stopped, emitted)
    }
}

private fun RecordedFrame.toComposedFrame(
    source: RecordingMetadata,
    target: PlaybackTarget,
    eventTimeNanos: Long,
    origin: PointerOrigin,
    pointerIdOffset: Int,
): ComposedFrame {
    val delta = ((target.rotation - source.rotation) % 360 + 360) % 360
    val scale = minOf(target.displayWidth, target.displayHeight).toFloat()
    val pointers = pointers.map { p ->
        val (nx, ny) = rotate(p.x, p.y, delta)
        PointerState(
            id = p.id + pointerIdOffset,
            x = nx * target.displayWidth,
            y = ny * target.displayHeight,
            pressure = p.pressure,
            size = p.size,
            touchMajor = p.touchMajor * scale,
            touchMinor = p.touchMinor * scale,
            toolMajor = p.toolMajor * scale,
            toolMinor = p.toolMinor * scale,
            origin = origin,
        )
    }
    require(pointers.all { it.id in 0..31 }) { "pointer id offset exceeds Android range" }
    return ComposedFrame(action, pointers, changedPointerId + pointerIdOffset, eventTimeNanos)
}

private fun rotate(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
    0 -> x to y
    90 -> (1f - y) to x
    180 -> (1f - x) to (1f - y)
    270 -> y to (1f - x)
    else -> error("invalid rotation")
}
