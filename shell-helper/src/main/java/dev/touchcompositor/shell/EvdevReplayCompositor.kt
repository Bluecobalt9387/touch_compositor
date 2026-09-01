package dev.touchcompositor.shell

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.touchcompositor.input.ComposedFrame
import dev.touchcompositor.input.DisplayGeometry
import dev.touchcompositor.input.RawAxisRange
import dev.touchcompositor.input.RawTouchGeometry
import dev.touchcompositor.input.PointerOrigin
import dev.touchcompositor.input.PointerState
import dev.touchcompositor.input.TouchAction

/**
 * Experimental Type-B evdev -> MotionEvent replay backend.
 * The native helper owns EVIOCGRAB; this class only parses and re-injects.
 */
class EvdevReplayCompositor(
    private val devicePath: String,
    private val rawMinX: Int,
    private val rawMaxX: Int,
    private val rawMinY: Int,
    private val rawMaxY: Int,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val grabHelperPath: String,
    private val demoVirtualTap: Boolean = false,
) {
    companion object {
        private const val EV_SYN = 0x00
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0x00
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_TOUCH_MINOR = 0x31
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
    }

    data class Contact(
        val slot: Int,
        var trackingId: Int = -1,
        var rawX: Int = 0,
        var rawY: Int = 0,
        var major: Int = 0,
        var minor: Int = 0,
    ) {
        fun copyContact() = copy()
    }

    private val rawGeometry = RawTouchGeometry(RawAxisRange(rawMinX, rawMaxX), RawAxisRange(rawMinY, rawMaxY))
    private val displayGeometry = DisplayGeometry(displayWidth, displayHeight, 0)

    private val log = PrintWriter(FileOutputStream("/data/local/tmp/touch_compositor_replay.log", false), true)
    private val slots = linkedMapOf<Int, Contact>()
    private var previous = linkedMapOf<Int, Contact>()
    private var currentSlot = 0
    private var frame = 0L
    private val virtualSlot = 19
    private var virtualActive = false
    private var virtualArmedAt = 0L
    private var virtualDownAt = 0L
    private var virtualTriggered = false

    private val sink by lazy { AndroidTouchFrameSink() }

    fun run() {
        val process = ProcessBuilder(grabHelperPath, devicePath, "60")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { process.destroy() } })
        log.println("REPLAY_START device=$devicePath raw=[$rawMinX,$rawMaxX]x[$rawMinY,$rawMaxY] display=${displayWidth}x${displayHeight}")
        try {
            BufferedInputStream(process.inputStream, 24 * 256).use { input ->
                val buf = ByteArray(24)
                while (true) {
                    var off = 0
                    while (off < buf.size) {
                        val n = input.read(buf, off, buf.size - off)
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
                        EV_ABS -> onAbs(code, value)
                        EV_SYN -> if (code == SYN_REPORT) onFrame()
                    }
                }
            }
        } finally {
            runCatching { process.destroy() }
            if (previous.isNotEmpty()) {
                // Do not leave a synthetic gesture logically held if the 20s prototype expires.
                injectUpAll(previous)
            }
            log.println("REPLAY_STOP frames=$frame")
            log.close()
        }
    }

    private fun onAbs(code: Int, value: Int) {
        when (code) {
            ABS_MT_SLOT -> currentSlot = value
            ABS_MT_TRACKING_ID -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.trackingId = value
            ABS_MT_POSITION_X -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.rawX = value
            ABS_MT_POSITION_Y -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.rawY = value
            ABS_MT_TOUCH_MAJOR -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.major = value
            ABS_MT_TOUCH_MINOR -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.minor = value
        }
    }

    private fun activeSnapshot(): LinkedHashMap<Int, Contact> =
        slots.values
            .filter { it.trackingId >= 0 }
            .sortedBy { it.slot }
            .associateByTo(linkedMapOf()) { it.slot }
            .mapValuesTo(linkedMapOf()) { it.value.copyContact() }

    private fun onFrame() {
        frame++
        val current = activeSnapshot()
        val prevKeys = previous.keys.toList()
        val curKeys = current.keys.toList()
        val removed = prevKeys.filter { slot ->
            val p = previous[slot]
            val c = current[slot]
            c == null || c.trackingId != p?.trackingId
        }
        val added = curKeys.filter { slot ->
            val p = previous[slot]
            val c = current[slot]
            p == null || p.trackingId != c?.trackingId
        }
        var emittedIdentityAction = false

        for (slot in removed.sortedByDescending { previous.keys.toList().indexOf(it) }) {
            if (!previous.containsKey(slot)) continue
            injectPointerUp(combinedPoints(), slot)
            previous.remove(slot)
            emittedIdentityAction = true
        }

        current.forEach { (k, v) ->
            if (previous.containsKey(k) && previous[k]?.trackingId == v.trackingId) {
                previous[k] = v.copyContact()
            }
        }

        for (slot in added.sorted()) {
            val c = current[slot] ?: continue
            previous[slot] = c.copyContact()
            injectPointerDown(combinedPoints(), slot)
            emittedIdentityAction = true
        }

        val now = SystemClock.uptimeMillis()
        if (demoVirtualTap && !virtualTriggered && current.size >= 2) {
            if (virtualArmedAt == 0L) virtualArmedAt = now
            if (now - virtualArmedAt >= 600L) {
                virtualActive = true
                virtualTriggered = true
                virtualDownAt = now
                injectPointerDown(combinedPoints(), virtualSlot)
                log.println("DEMO_VIRTUAL_DOWN slot=$virtualSlot")
                emittedIdentityAction = true
            }
        } else if (!virtualTriggered && current.size < 2) {
            virtualArmedAt = 0L
        }

        if (virtualActive && (now - virtualDownAt >= 180L || current.size < 2)) {
            injectPointerUp(combinedPoints(), virtualSlot)
            virtualActive = false
            log.println("DEMO_VIRTUAL_UP slot=$virtualSlot")
            emittedIdentityAction = true
        }

        if (!emittedIdentityAction && combinedPoints().isNotEmpty()) {
            injectMotion(combinedPoints(), 2)
        }

        if (current.isEmpty() && !virtualActive) {
            previous.clear()
        }

        if (frame % 120L == 0L) {
            log.println("FRAME $frame contacts=${current.size} virtual=${if (virtualActive) 1 else 0}")
        }
    }

    private fun combinedPoints(): LinkedHashMap<Int, Contact> {
        val out = linkedMapOf<Int, Contact>()
        previous.entries.sortedBy { it.key }.forEach { (k, v) -> out[k] = v.copyContact() }
        if (virtualActive) {
            out[virtualSlot] = Contact(
                slot = virtualSlot,
                trackingId = 1_000_019,
                rawX = (rawMinX + 0.78f * (rawMaxX - rawMinX)).toInt(),
                rawY = (rawMinY + 0.50f * (rawMaxY - rawMinY)).toInt(),
                major = 55,
                minor = 52,
            )
        }
        return out
    }

    private fun injectPointerDown(points: LinkedHashMap<Int, Contact>, addedSlot: Int) {
        val action = if (points.size == 1) TouchAction.DOWN else TouchAction.POINTER_DOWN
        emit(points, action, addedSlot)
    }

    private fun injectPointerUp(points: LinkedHashMap<Int, Contact>, removedSlot: Int) {
        if (points.isEmpty()) return
        val action = if (points.size == 1) TouchAction.UP else TouchAction.POINTER_UP
        emit(points, action, removedSlot)
    }

    private fun injectUpAll(points: LinkedHashMap<Int, Contact>) {
        if (points.isEmpty()) return
        emit(points, TouchAction.CANCEL, points.keys.first())
    }

    private fun injectMotion(points: Map<Int, Contact>, action: Int): Boolean {
        val logical = when (action) {
            2 -> TouchAction.MOVE
            3 -> TouchAction.CANCEL
            else -> error("legacy action $action is not supported")
        }
        return emit(points, logical, points.keys.firstOrNull() ?: 0)
    }

    private fun emit(points: Map<Int, Contact>, action: TouchAction, changedPointerId: Int): Boolean {
        if (points.isEmpty()) return action == TouchAction.CANCEL
        val states = points.values.sortedBy { it.slot }.map { c ->
            val major = c.major.coerceAtLeast(1).toFloat()
            PointerState(
                id = c.slot,
                x = displayGeometry.rawToScreen(c.rawX, c.rawY, rawGeometry).x,
                y = displayGeometry.rawToScreen(c.rawX, c.rawY, rawGeometry).y,
                pressure = (major / 31f).coerceIn(0.05f, 1f),
                size = (major / 31f).coerceIn(0.01f, 1f),
                touchMajor = major,
                touchMinor = c.minor.coerceAtLeast(1).toFloat(),
                toolMajor = major,
                toolMinor = c.minor.coerceAtLeast(1).toFloat(),
                origin = if (c.slot == virtualSlot) PointerOrigin.SYNTHETIC else PointerOrigin.PHYSICAL,
            )
        }
        val shouldLog = action != TouchAction.MOVE || frame % 20L == 0L
        if (shouldLog) {
            val ids = states.joinToString(",") { it.id.toString() }
            log.println("OUT frame=$frame action=$action pointers=${states.size} ids=$ids")
        }
        return sink.emit(ComposedFrame(action, states, changedPointerId, System.nanoTime()))
    }
}
