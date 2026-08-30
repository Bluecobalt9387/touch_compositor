package dev.touchcompositor.shell

import dev.touchcompositor.input.ComposedFrame
import dev.touchcompositor.input.PlaybackOptions
import dev.touchcompositor.input.PlaybackResult
import dev.touchcompositor.input.PlaybackTarget
import dev.touchcompositor.input.PointerIdAllocator
import dev.touchcompositor.input.PointerOrigin
import dev.touchcompositor.input.PointerState
import dev.touchcompositor.input.RecordingMetadata
import dev.touchcompositor.input.TouchAction
import dev.touchcompositor.input.TouchAttributes
import dev.touchcompositor.input.TouchCapabilities
import dev.touchcompositor.input.TouchEngineApi
import dev.touchcompositor.input.TouchFrameSink
import dev.touchcompositor.input.TouchPlayer
import dev.touchcompositor.input.TouchRecorder
import dev.touchcompositor.input.TouchRecording
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Long-lived touch engine running under uid=shell.
 *
 * The physical touchscreen is EVIOCGRAB'ed and replayed through [sink]. Synthetic
 * and playback pointers are inserted into that same MotionEvent stream, so adding
 * an automation pointer does not cancel an in-progress physical gesture.
 */
class LiveTouchEngine(
    private val devicePath: String,
    private val rawMaxX: Int,
    private val rawMaxY: Int,
    private val baseDisplayWidth: Int,
    private val baseDisplayHeight: Int,
    initialRotation: Int,
    private val grabHelperPath: String,
    private val hasHardwarePressure: Boolean = false,
    private val sink: TouchFrameSink = AndroidTouchFrameSink(),
) : TouchEngineApi, AutoCloseable {
    companion object {
        private const val EV_SYN = 0x00
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0x00
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_TOUCH_MINOR = 0x31
        private const val ABS_MT_ORIENTATION = 0x34
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val ABS_MT_PRESSURE = 0x3a
    }

    private data class RawContact(
        val slot: Int,
        var trackingId: Int = -1,
        var x: Int = 0,
        var y: Int = 0,
        var major: Int = 0,
        var minor: Int = 0,
        var pressure: Int = 0,
        var orientation: Int = 0,
    )

    private data class ActivePointer(
        val externalId: Int,
        val androidId: Int,
        val origin: PointerOrigin,
        var state: PointerState,
        var trackingId: Int = -1,
    )

    data class RecordingRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        init { require(left <= right && top <= bottom) }
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    override val capabilities = TouchCapabilities(
        canReadPhysicalTouch = true,
        canGrabPhysicalTouch = true,
        canInjectTouch = true,
        canComposePhysicalAndSynthetic = true,
        maxPointers = 32,
        hasHardwarePressure = hasHardwarePressure,
    )

    private val lock = Any()
    private var displayWidth = if (initialRotation == 90 || initialRotation == 270) baseDisplayHeight else baseDisplayWidth
    private var displayHeight = if (initialRotation == 90 || initialRotation == 270) baseDisplayWidth else baseDisplayHeight
    private var displayRotation = initialRotation
    private val allocator = PointerIdAllocator(32)
    private val rawSlots = linkedMapOf<Int, RawContact>()
    private val physical = linkedMapOf<Int, ActivePointer>() // key = evdev slot
    private val synthetic = linkedMapOf<Int, ActivePointer>() // key = API external id
    private val playback = linkedMapOf<Int, ActivePointer>() // key = recorded external id
    private var currentRawSlot = 0

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null

    private var recorder: TouchRecorder? = null
    private var recordingMetadata: RecordingMetadata? = null
    private val recordingExclusions = mutableListOf<RecordingRect>()
    private val recordingRawLast = linkedMapOf<Int, RawContact>()
    private val recordingActive = linkedMapOf<Int, RawContact>()
    private val recordingExcludedSlots = mutableSetOf<Int>()
    private val player = TouchPlayer()

    val isRunning: Boolean get() = running.get()
    val isRecording: Boolean get() = synchronized(lock) { recorder != null }

    fun start() {
        check(running.compareAndSet(false, true)) { "touch engine already running" }
        val child = ProcessBuilder(grabHelperPath, devicePath, "0").start()
        process = child
        try {
            val ready = child.errorStream.bufferedReader().readLine()
            check(ready?.startsWith("EVGRAB_READY ") == true) {
                "grab helper failed to start: ${ready ?: "no status"}"
            }
        } catch (t: Throwable) {
            running.set(false)
            runCatching { child.destroyForcibly() }
            process = null
            throw t
        }

        readerThread = Thread({ readEvdev(child) }, "touch-compositor-evdev").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun readEvdev(child: Process) {
        try {
            BufferedInputStream(child.inputStream, 24 * 256).use { input ->
                val eventSize = 24 // app_process is arm64 on supported Android devices.
                val buf = ByteArray(eventSize)
                while (running.get()) {
                    var off = 0
                    while (off < eventSize) {
                        val n = input.read(buf, off, eventSize - off)
                        if (n < 0) return
                        off += n
                    }
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    bb.long
                    bb.long
                    val type = bb.short.toInt() and 0xffff
                    val code = bb.short.toInt() and 0xffff
                    val value = bb.int
                    when (type) {
                        EV_ABS -> synchronized(lock) { onAbs(code, value) }
                        EV_SYN -> if (code == SYN_REPORT) synchronized(lock) { onPhysicalFrame(System.nanoTime()) }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) System.err.println("LIVE_EVDEV_ERROR ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            running.set(false)
        }
    }

    private fun onAbs(code: Int, value: Int) {
        fun contact() = rawSlots.getOrPut(currentRawSlot) { RawContact(currentRawSlot) }
        when (code) {
            ABS_MT_SLOT -> currentRawSlot = value
            ABS_MT_TRACKING_ID -> contact().trackingId = value
            ABS_MT_POSITION_X -> contact().x = value
            ABS_MT_POSITION_Y -> contact().y = value
            ABS_MT_TOUCH_MAJOR -> contact().major = value
            ABS_MT_TOUCH_MINOR -> contact().minor = value
            ABS_MT_PRESSURE -> contact().pressure = value
            ABS_MT_ORIENTATION -> contact().orientation = value
        }
    }

    private fun onPhysicalFrame(now: Long) {
        val current = rawSlots.values
            .filter { it.trackingId >= 0 }
            .sortedBy { it.slot }
            .associateByTo(linkedMapOf()) { it.slot }
            .mapValuesTo(linkedMapOf()) { it.value.copy() }

        recordRawPhysical(current, now)

        val removed = physical.keys.toList().filter { slot ->
            val p = physical[slot]
            val c = current[slot]
            c == null || c.trackingId != p?.trackingId
        }
        val added = current.keys.toList().filter { slot ->
            val p = physical[slot]
            val c = current[slot]
            p == null || p.trackingId != c?.trackingId
        }
        var emittedIdentity = false

        for (slot in removed.sortedByDescending { physical.keys.toList().indexOf(it) }) {
            val p = physical[slot] ?: continue
            val points = combinedStates()
            val action = if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP
            emitCombined(action, p.androidId, now, points)
            physical.remove(slot)
            allocator.release(PointerOrigin.PHYSICAL, slot)
            emittedIdentity = true
        }

        // Update surviving physical contacts before any new pointer is emitted.
        current.forEach { (slot, raw) ->
            val p = physical[slot]
            if (p != null && p.trackingId == raw.trackingId) {
                p.state = raw.toPointerState(p.androidId)
            }
        }

        for (slot in added.sorted()) {
            val raw = current[slot] ?: continue
            val androidId = allocator.acquire(PointerOrigin.PHYSICAL, slot)
            val p = ActivePointer(
                externalId = slot,
                androidId = androidId,
                origin = PointerOrigin.PHYSICAL,
                state = raw.toPointerState(androidId),
                trackingId = raw.trackingId,
            )
            physical[slot] = p
            val points = combinedStates()
            val action = if (points.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN
            emitCombined(action, androidId, now, points)
            emittedIdentity = true
        }

        if (!emittedIdentity && physical.isNotEmpty()) {
            emitCombined(TouchAction.MOVE, physical.values.first().androidId, now)
        }
    }

    private fun RawContact.toPointerState(androidId: Int): PointerState {
        val u = x.toFloat() / (rawMaxX + 1).toFloat()
        val v = y.toFloat() / (rawMaxY + 1).toFloat()
        val (logicalX, logicalY) = when (displayRotation) {
            0 -> u * displayWidth to v * displayHeight
            90 -> (1f - v) * displayWidth to u * displayHeight
            180 -> (1f - u) * displayWidth to (1f - v) * displayHeight
            270 -> v * displayWidth to (1f - u) * displayHeight
            else -> error("unsupported display rotation $displayRotation")
        }
        return pointerState(androidId, logicalX, logicalY)
    }

    /** Canonical natural-orientation coordinates used by recordings. */
    private fun RawContact.toNaturalPointerState(androidId: Int): PointerState {
        val x = this.x.toFloat() * baseDisplayWidth / (rawMaxX + 1).toFloat()
        val y = this.y.toFloat() * baseDisplayHeight / (rawMaxY + 1).toFloat()
        return pointerState(androidId, x, y)
    }

    private fun RawContact.pointerState(androidId: Int, x: Float, y: Float): PointerState {
        val majorPx = max(1, major).toFloat()
        val minorPx = max(1, minor).toFloat()
        val p = if (pressure > 0) pressure.toFloat().coerceAtLeast(0f)
        else (majorPx / 31f).coerceIn(0.05f, 1f)
        return PointerState(
            id = androidId, x = x, y = y,
            pressure = p, size = (majorPx / 31f).coerceIn(0.01f, 1f),
            touchMajor = majorPx, touchMinor = minorPx,
            toolMajor = majorPx, toolMinor = minorPx,
            origin = PointerOrigin.PHYSICAL,
        )
    }

    fun updateDisplay(width: Int, height: Int, rotation: Int) {
        require(width > 0 && height > 0)
        require(rotation in setOf(0, 90, 180, 270))
        synchronized(lock) {
            if (displayWidth == width && displayHeight == height && displayRotation == rotation) return
            // Android cancels active gestures across a display rotation. Mirror that
            // behavior, then the next evdev SYN_REPORT re-acquires held fingers.
            if (combinedStates().isNotEmpty()) {
                runCatching {
                    sink.emit(ComposedFrame(TouchAction.CANCEL, combinedStates(), combinedStates().first().id, System.nanoTime()))
                }
                player.stop()
                physical.clear()
                synthetic.clear()
                playback.clear()
                allocator.reset()
            }
            displayWidth = width
            displayHeight = height
            displayRotation = rotation
        }
    }

    override fun down(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) {
        synchronized(lock) {
            require(pointerId >= 0)
            check(pointerId !in synthetic) { "synthetic pointer $pointerId already down" }
            val androidId = allocator.acquire(PointerOrigin.SYNTHETIC, pointerId)
            synthetic[pointerId] = ActivePointer(
                pointerId,
                androidId,
                PointerOrigin.SYNTHETIC,
                attributes.toPointerState(androidId, x, y, PointerOrigin.SYNTHETIC),
            )
            val points = combinedStates()
            emitCombined(if (points.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN, androidId, System.nanoTime(), points)
        }
    }

    override fun move(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) {
        synchronized(lock) {
            val p = synthetic[pointerId] ?: error("synthetic pointer $pointerId is not down")
            p.state = attributes.toPointerState(p.androidId, x, y, PointerOrigin.SYNTHETIC)
            emitCombined(TouchAction.MOVE, p.androidId, System.nanoTime())
        }
    }

    override fun up(pointerId: Int, x: Float, y: Float, attributes: TouchAttributes) {
        synchronized(lock) {
            val p = synthetic[pointerId] ?: error("synthetic pointer $pointerId is not down")
            p.state = attributes.toPointerState(p.androidId, x, y, PointerOrigin.SYNTHETIC)
            val points = combinedStates()
            emitCombined(if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP, p.androidId, System.nanoTime(), points)
            synthetic.remove(pointerId)
            allocator.release(PointerOrigin.SYNTHETIC, pointerId)
        }
    }

    /** Safely releases only automation pointers; physical fingers remain uninterrupted. */
    override fun cancelAll() {
        synchronized(lock) { releaseOrigin(PointerOrigin.SYNTHETIC, System.nanoTime()) }
    }

    fun setRecordingExclusions(rects: List<RecordingRect>) {
        synchronized(lock) {
            recordingExclusions.clear()
            recordingExclusions.addAll(rects)
        }
    }

    override fun startRecording(metadata: RecordingMetadata) {
        synchronized(lock) {
            check(recorder == null) { "already recording" }
            check(physical.isEmpty()) { "lift physical fingers before starting a recording" }
            recorder = TouchRecorder().also { it.start(metadata, System.nanoTime()) }
            recordingMetadata = metadata
            recordingRawLast.clear()
            recordingActive.clear()
            recordingExcludedSlots.clear()
        }
    }

    override fun stopRecording(): TouchRecording = synchronized(lock) {
        val r = recorder ?: error("not recording")
        val now = System.nanoTime()
        if (recordingActive.isNotEmpty()) {
            val states = recordingStates()
            r.record(ComposedFrame(TouchAction.CANCEL, states, states.first().id, now))
        }
        val result = r.stop(now)
        recorder = null
        recordingMetadata = null
        recordingRawLast.clear()
        recordingActive.clear()
        recordingExcludedSlots.clear()
        result
    }

    /** Recorder is driven from raw physical frames and can exclude overlay gestures cleanly. */
    private fun recordRawPhysical(current: LinkedHashMap<Int, RawContact>, now: Long) {
        val r = recorder ?: return
        val removed = recordingRawLast.keys.toList().filter { slot ->
            val old = recordingRawLast[slot]
            val cur = current[slot]
            cur == null || cur.trackingId != old?.trackingId
        }
        val added = current.keys.toList().filter { slot ->
            val old = recordingRawLast[slot]
            val cur = current[slot]
            old == null || old.trackingId != cur?.trackingId
        }
        var emittedIdentity = false

        for (slot in removed.sortedByDescending { recordingActive.keys.toList().indexOf(it) }) {
            if (slot in recordingExcludedSlots) {
                recordingExcludedSlots.remove(slot)
                continue
            }
            if (!recordingActive.containsKey(slot)) continue
            val states = recordingStates()
            val action = if (states.size == 1) TouchAction.UP else TouchAction.POINTER_UP
            r.record(ComposedFrame(action, states, slot, now))
            recordingActive.remove(slot)
            emittedIdentity = true
        }

        current.forEach { (slot, raw) ->
            val prev = recordingActive[slot]
            if (prev != null && prev.trackingId == raw.trackingId) recordingActive[slot] = raw.copy()
        }

        for (slot in added.sorted()) {
            val raw = current[slot] ?: continue
            val state = raw.toPointerState(slot)
            if (recordingExclusions.any { it.contains(state.x, state.y) }) {
                recordingExcludedSlots += slot
                continue
            }
            recordingActive[slot] = raw.copy()
            val states = recordingStates()
            val action = if (states.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN
            r.record(ComposedFrame(action, states, slot, now))
            emittedIdentity = true
        }

        if (!emittedIdentity && recordingActive.isNotEmpty()) {
            val states = recordingStates()
            r.record(ComposedFrame(TouchAction.MOVE, states, states.first().id, now))
        }

        recordingRawLast.clear()
        current.forEach { (slot, raw) -> recordingRawLast[slot] = raw.copy() }
    }

    private fun recordingStates(): List<PointerState> = recordingActive.values
        .sortedBy { it.slot }
        .map { it.toNaturalPointerState(it.slot) }

    override fun play(
        recording: TouchRecording,
        target: PlaybackTarget,
        options: PlaybackOptions,
    ): PlaybackResult = try {
        player.play(recording, target, playbackSink, options, PointerOrigin.PLAYBACK)
    } finally {
        synchronized(lock) { releaseOrigin(PointerOrigin.PLAYBACK, System.nanoTime()) }
    }

    override fun pausePlayback() = player.pause()
    override fun resumePlayback() = player.resume()
    override fun stopPlayback() = player.stop()

    private val playbackSink = TouchFrameSink { frame ->
        synchronized(lock) { applyPlaybackFrame(frame) }
        true
    }

    private fun applyPlaybackFrame(frame: ComposedFrame) {
        when (frame.action) {
            TouchAction.DOWN, TouchAction.POINTER_DOWN -> {
                updateExistingPlayback(frame)
                val incoming = frame.pointers.first { it.id == frame.changedPointerId }
                check(frame.changedPointerId !in playback) { "playback pointer already active" }
                val androidId = allocator.acquire(PointerOrigin.PLAYBACK, frame.changedPointerId)
                playback[frame.changedPointerId] = ActivePointer(
                    frame.changedPointerId,
                    androidId,
                    PointerOrigin.PLAYBACK,
                    incoming.copy(id = androidId, origin = PointerOrigin.PLAYBACK),
                )
                val points = combinedStates()
                emitCombined(if (points.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN, androidId, frame.eventTimeNanos, points)
            }
            TouchAction.MOVE -> {
                updateExistingPlayback(frame)
                if (playback.isNotEmpty()) emitCombined(TouchAction.MOVE, playback.values.first().androidId, frame.eventTimeNanos)
            }
            TouchAction.UP, TouchAction.POINTER_UP -> {
                updateExistingPlayback(frame)
                val p = playback[frame.changedPointerId] ?: return
                val points = combinedStates()
                emitCombined(if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP, p.androidId, frame.eventTimeNanos, points)
                playback.remove(frame.changedPointerId)
                allocator.release(PointerOrigin.PLAYBACK, frame.changedPointerId)
            }
            TouchAction.CANCEL -> releaseOrigin(PointerOrigin.PLAYBACK, frame.eventTimeNanos)
        }
    }

    private fun updateExistingPlayback(frame: ComposedFrame) {
        for (incoming in frame.pointers) {
            val p = playback[incoming.id] ?: continue
            p.state = incoming.copy(id = p.androidId, origin = PointerOrigin.PLAYBACK)
        }
    }

    private fun releaseOrigin(origin: PointerOrigin, now: Long) {
        val map = when (origin) {
            PointerOrigin.SYNTHETIC -> synthetic
            PointerOrigin.PLAYBACK -> playback
            PointerOrigin.PHYSICAL -> error("physical pointers are owned by evdev")
        }
        while (map.isNotEmpty()) {
            val key = map.keys.last()
            val p = map.getValue(key)
            val points = combinedStates()
            emitCombined(if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP, p.androidId, now, points)
            map.remove(key)
            allocator.release(origin, key)
        }
    }

    private fun TouchAttributes.toPointerState(
        androidId: Int,
        x: Float,
        y: Float,
        origin: PointerOrigin,
    ) = PointerState(
        id = androidId,
        x = x,
        y = y,
        pressure = pressure,
        size = size,
        touchMajor = touchMajor,
        touchMinor = touchMinor,
        toolMajor = toolMajor,
        toolMinor = toolMinor,
        origin = origin,
    )

    private fun physicalStates(): List<PointerState> =
        physical.values.map { it.state }.sortedBy { it.id }

    private fun combinedStates(): List<PointerState> = buildList {
        addAll(physical.values.map { it.state })
        addAll(synthetic.values.map { it.state })
        addAll(playback.values.map { it.state })
    }.sortedBy { it.id }

    private fun emitCombined(
        action: TouchAction,
        changedPointerId: Int,
        now: Long,
        points: List<PointerState> = combinedStates(),
    ) {
        if (points.isEmpty()) return
        check(sink.emit(ComposedFrame(action, points, changedPointerId, now))) {
            "Android rejected touch frame $action"
        }
    }

    fun status(): String = synchronized(lock) {
        "physical=${physical.size} synthetic=${synthetic.size} playback=${playback.size} recording=${recorder != null} player=${player.state} display=${displayWidth}x${displayHeight} rotation=$displayRotation"
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        stopPlayback()
        synchronized(lock) {
            if (combinedStates().isNotEmpty()) {
                runCatching {
                    sink.emit(
                        ComposedFrame(
                            TouchAction.CANCEL,
                            combinedStates(),
                            combinedStates().first().id,
                            System.nanoTime(),
                        )
                    )
                }
            }
            synthetic.clear()
            playback.clear()
            physical.clear()
            allocator.reset()
            recorder?.cancel()
            recorder = null
            recordingRawLast.clear()
            recordingActive.clear()
            recordingExcludedSlots.clear()
        }
        runCatching { process?.destroy() }
        runCatching { readerThread?.join(1000) }
        process = null
        readerThread = null
    }
}
