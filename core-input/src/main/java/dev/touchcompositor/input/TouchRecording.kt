package dev.touchcompositor.input

/** Rotation in degrees clockwise. */
data class RecordingMetadata(
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int = 0,
    val formatVersion: Int = 1,
) {
    init {
        require(displayWidth > 0 && displayHeight > 0)
        require(rotation in setOf(0, 90, 180, 270))
    }
}

/** Coordinates and contact dimensions are normalized to the source display. */
data class RecordedPointer(
    val id: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val toolMajor: Float,
    val toolMinor: Float,
    val origin: PointerOrigin,
)

data class RecordedFrame(
    val offsetNanos: Long,
    val action: TouchAction,
    val changedPointerId: Int,
    val pointers: List<RecordedPointer>,
)

data class TouchRecording(
    val metadata: RecordingMetadata,
    val durationNanos: Long,
    val frames: List<RecordedFrame>,
) {
    init {
        require(durationNanos >= 0)
        require(frames.zipWithNext().all { (a, b) -> a.offsetNanos <= b.offsetNanos })
    }
}

class TouchRecorder {
    private var metadata: RecordingMetadata? = null
    private var startedAtNanos = 0L
    private val frames = mutableListOf<RecordedFrame>()

    val isRecording: Boolean get() = metadata != null

    @Synchronized
    fun start(metadata: RecordingMetadata, startTimeNanos: Long) {
        check(!isRecording) { "already recording" }
        this.metadata = metadata
        startedAtNanos = startTimeNanos
        frames.clear()
    }

    @Synchronized
    fun record(frame: ComposedFrame) {
        val m = metadata ?: return
        val scale = minOf(m.displayWidth, m.displayHeight).toFloat()
        frames += RecordedFrame(
            offsetNanos = (frame.eventTimeNanos - startedAtNanos).coerceAtLeast(0L),
            action = frame.action,
            changedPointerId = frame.changedPointerId,
            pointers = frame.pointers.map { p ->
                RecordedPointer(
                    id = p.id,
                    x = (p.x / m.displayWidth).coerceIn(0f, 1f),
                    y = (p.y / m.displayHeight).coerceIn(0f, 1f),
                    pressure = p.pressure,
                    size = p.size,
                    touchMajor = p.touchMajor / scale,
                    touchMinor = p.touchMinor / scale,
                    toolMajor = p.toolMajor / scale,
                    toolMinor = p.toolMinor / scale,
                    origin = p.origin,
                )
            },
        )
    }

    @Synchronized
    fun stop(stopTimeNanos: Long): TouchRecording {
        val m = metadata ?: error("not recording")
        val duration = maxOf(
            (stopTimeNanos - startedAtNanos).coerceAtLeast(0L),
            frames.lastOrNull()?.offsetNanos ?: 0L,
        )
        val result = TouchRecording(m, duration, frames.toList())
        metadata = null
        frames.clear()
        return result
    }

    @Synchronized
    fun cancel() {
        metadata = null
        frames.clear()
    }
}
