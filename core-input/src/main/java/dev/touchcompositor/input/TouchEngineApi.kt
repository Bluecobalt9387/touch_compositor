package dev.touchcompositor.input

data class TouchCapabilities(
    val canReadPhysicalTouch: Boolean,
    val canGrabPhysicalTouch: Boolean,
    val canInjectTouch: Boolean,
    val canComposePhysicalAndSynthetic: Boolean,
    val maxPointers: Int = 32,
    val hasHardwarePressure: Boolean = false,
)

data class TouchAttributes(
    val pressure: Float = 1f,
    val size: Float = 0.05f,
    val touchMajor: Float = 5f,
    val touchMinor: Float = 5f,
    val toolMajor: Float = touchMajor,
    val toolMinor: Float = touchMinor,
)

/** Typed high-level API. No arbitrary shell command is exposed. */
interface TouchEngineApi {
    val capabilities: TouchCapabilities

    fun down(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes = TouchAttributes())
    fun move(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes = TouchAttributes())
    fun up(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes = TouchAttributes())
    fun cancelAll()

    fun startRecording(metadata: RecordingMetadata)
    fun stopRecording(): TouchRecording

    fun play(
        recording: TouchRecording,
        target: PlaybackTarget,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackResult
    fun pausePlayback()
    fun resumePlayback()
    fun stopPlayback()
}
