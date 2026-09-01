package dev.touchcompositor.shell

import dev.touchcompositor.input.ComposedFrame
import dev.touchcompositor.input.DisplayGeometry
import dev.touchcompositor.input.RawAxisRange
import dev.touchcompositor.input.RawTouchGeometry
import dev.touchcompositor.input.PointerOrigin
import dev.touchcompositor.input.PointerState
import dev.touchcompositor.input.RecordingMetadata
import dev.touchcompositor.input.TouchAction
import dev.touchcompositor.input.TouchRecorder
import dev.touchcompositor.input.TouchRecording
import dev.touchcompositor.input.TouchRecordingCodec
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Non-grabbing Type-B evdev recorder. Physical touches still reach Android normally. */
class EvdevRecorder(
    private val devicePath: String,
    rawMinX: Int,
    rawMaxX: Int,
    rawMinY: Int,
    rawMaxY: Int,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val rotation: Int,
) {
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

    private data class Contact(
        val slot: Int,
        var trackingId: Int = -1,
        var x: Int = 0,
        var y: Int = 0,
        var major: Int = 0,
        var minor: Int = 0,
        var pressure: Int = 0,
        var orientation: Int = 0,
    )

    private val rawGeometry = RawTouchGeometry(RawAxisRange(rawMinX, rawMaxX), RawAxisRange(rawMinY, rawMaxY))
    private val displayGeometry = DisplayGeometry(displayWidth, displayHeight, rotation)

    private val slots = linkedMapOf<Int, Contact>()
    private var previous = linkedMapOf<Int, Contact>()
    private var currentSlot = 0
    private val recorder = TouchRecorder()
    private var recorderStarted = false

    fun recordTo(outputPath: String, durationSeconds: Int): TouchRecording {
        require(durationSeconds in 1..3600)
        val eventSize = if (System.getProperty("os.arch")?.contains("64") == true) 24 else 16
        val inputFile = FileInputStream(devicePath)
        val running = AtomicBoolean(true)
        val timer = Thread {
            try {
                Thread.sleep(durationSeconds * 1000L)
            } catch (_: InterruptedException) {
            }
            running.set(false)
            runCatching { inputFile.close() }
        }.apply { isDaemon = true; start() }

        try {
            BufferedInputStream(inputFile, eventSize * 256).use { input ->
                val buf = ByteArray(eventSize)
                while (running.get()) {
                    var off = 0
                    while (off < eventSize) {
                        val n = try { input.read(buf, off, eventSize - off) } catch (_: Throwable) { -1 }
                        if (n < 0) break
                        off += n
                    }
                    if (off < eventSize) break
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    if (eventSize == 24) { bb.long; bb.long } else { bb.int; bb.int }
                    val type = bb.short.toInt() and 0xffff
                    val code = bb.short.toInt() and 0xffff
                    val value = bb.int
                    when (type) {
                        EV_ABS -> onAbs(code, value)
                        EV_SYN -> if (code == SYN_REPORT) onFrame(System.nanoTime())
                    }
                }
            }
        } finally {
            running.set(false)
            timer.interrupt()
        }

        val end = System.nanoTime()
        if (!recorderStarted) {
            recorder.start(RecordingMetadata(displayWidth, displayHeight, rotation), end)
            recorderStarted = true
        }
        if (previous.isNotEmpty()) {
            recorder.record(
                ComposedFrame(
                    TouchAction.CANCEL,
                    pointerStates(previous),
                    previous.keys.first(),
                    end,
                )
            )
            previous.clear()
        }
        val recording = recorder.stop(end)
        FileOutputStream(outputPath).use { TouchRecordingCodec.write(recording, it) }
        return recording
    }

    private fun onAbs(code: Int, value: Int) {
        val c = { slots.getOrPut(currentSlot) { Contact(currentSlot) } }
        when (code) {
            ABS_MT_SLOT -> currentSlot = value
            ABS_MT_TRACKING_ID -> c().trackingId = value
            ABS_MT_POSITION_X -> c().x = value
            ABS_MT_POSITION_Y -> c().y = value
            ABS_MT_TOUCH_MAJOR -> c().major = value
            ABS_MT_TOUCH_MINOR -> c().minor = value
            ABS_MT_PRESSURE -> c().pressure = value
            ABS_MT_ORIENTATION -> c().orientation = value
        }
    }

    private fun activeSnapshot(): LinkedHashMap<Int, Contact> =
        slots.values.filter { it.trackingId >= 0 }
            .sortedBy { it.slot }
            .associateByTo(linkedMapOf()) { it.slot }
            .mapValuesTo(linkedMapOf()) { it.value.copy() }

    private fun onFrame(now: Long) {
        val current = activeSnapshot()
        if (!recorderStarted) {
            recorder.start(RecordingMetadata(displayWidth, displayHeight, rotation), now)
            recorderStarted = true
        }
        val removed = previous.keys.toList().filter { slot ->
            val p = previous[slot]
            val c = current[slot]
            c == null || c.trackingId != p?.trackingId
        }
        val added = current.keys.toList().filter { slot ->
            val p = previous[slot]
            val c = current[slot]
            p == null || p.trackingId != c?.trackingId
        }
        var identityAction = false

        for (slot in removed.sortedByDescending { previous.keys.toList().indexOf(it) }) {
            if (!previous.containsKey(slot)) continue
            val action = if (previous.size == 1) TouchAction.UP else TouchAction.POINTER_UP
            recorder.record(ComposedFrame(action, pointerStates(previous), slot, now))
            previous.remove(slot)
            identityAction = true
        }

        current.forEach { (slot, contact) ->
            if (previous[slot]?.trackingId == contact.trackingId) previous[slot] = contact.copy()
        }

        for (slot in added.sorted()) {
            val contact = current[slot] ?: continue
            previous[slot] = contact.copy()
            val action = if (previous.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN
            recorder.record(ComposedFrame(action, pointerStates(previous), slot, now))
            identityAction = true
        }

        if (!identityAction && previous.isNotEmpty()) {
            previous.clear()
            current.forEach { (k, v) -> previous[k] = v.copy() }
            recorder.record(ComposedFrame(TouchAction.MOVE, pointerStates(previous), previous.keys.first(), now))
        }
    }

    private fun pointerStates(points: Map<Int, Contact>): List<PointerState> = points.values
        .sortedBy { it.slot }
        .map { c ->
            val major = c.major.coerceAtLeast(1).toFloat()
            val pressure = if (c.pressure > 0) c.pressure.toFloat() else (major / 31f).coerceIn(0.05f, 1f)
            val screen = displayGeometry.rawToScreen(c.x, c.y, rawGeometry)
            PointerState(
                id = c.slot,
                x = screen.x,
                y = screen.y,
                pressure = pressure,
                size = (major / 31f).coerceIn(0.01f, 1f),
                touchMajor = major,
                touchMinor = c.minor.coerceAtLeast(1).toFloat(),
                toolMajor = major,
                toolMinor = c.minor.coerceAtLeast(1).toFloat(),
                origin = PointerOrigin.PHYSICAL,
            )
        }
}
