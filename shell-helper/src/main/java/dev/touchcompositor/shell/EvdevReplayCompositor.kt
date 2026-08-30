package dev.touchcompositor.shell

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Experimental Type-B evdev -> MotionEvent replay backend.
 * The native helper owns EVIOCGRAB; this class only parses and re-injects.
 */
class EvdevReplayCompositor(
    private val devicePath: String,
    private val rawMaxX: Int,
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
        private const val SOURCE_TOUCHSCREEN = 0x1002
        private const val INJECT_WAIT_FOR_RESULT = 1
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

    private val log = PrintWriter(FileOutputStream("/data/local/tmp/touch_compositor_replay.log", false), true)
    private val slots = linkedMapOf<Int, Contact>()
    private var previous = linkedMapOf<Int, Contact>()
    private var currentSlot = 0
    private var downTime = 0L
    private var frame = 0L
    private val virtualSlot = 19
    private var virtualActive = false
    private var virtualArmedAt = 0L
    private var virtualDownAt = 0L
    private var virtualTriggered = false

    private val injector by lazy { InputInjectorCompat() }

    fun run() {
        val process = ProcessBuilder(grabHelperPath, devicePath, "60")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { process.destroy() } })
        log.println("REPLAY_START device=$devicePath raw=${rawMaxX + 1}x${rawMaxY + 1} display=${displayWidth}x${displayHeight}")
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
            if (combinedPoints().isEmpty()) downTime = 0L
        }

        current.forEach { (k, v) ->
            if (previous.containsKey(k) && previous[k]?.trackingId == v.trackingId) {
                previous[k] = v.copyContact()
            }
        }

        for (slot in added.sorted()) {
            val c = current[slot] ?: continue
            previous[slot] = c.copyContact()
            if (combinedPoints().size == 1) downTime = SystemClock.uptimeMillis()
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
            if (combinedPoints().isEmpty()) downTime = 0L
        }

        if (!emittedIdentityAction && combinedPoints().isNotEmpty()) {
            injectMotion(combinedPoints(), MotionEvent.ACTION_MOVE)
        }

        if (current.isEmpty() && !virtualActive) {
            previous.clear()
            downTime = 0L
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
                rawX = (displayWidth * 0.78f / displayWidth * (rawMaxX + 1)).toInt(),
                rawY = (displayHeight * 0.50f / displayHeight * (rawMaxY + 1)).toInt(),
                major = 55,
                minor = 52,
            )
        }
        return out
    }

    private fun injectPointerDown(points: LinkedHashMap<Int, Contact>, addedSlot: Int) {
        val sorted = points.values.sortedBy { it.slot }
        val action = if (sorted.size == 1) {
            MotionEvent.ACTION_DOWN
        } else {
            val index = sorted.indexOfFirst { it.slot == addedSlot }
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        injectMotion(points, action)
    }

    private fun injectPointerUp(points: LinkedHashMap<Int, Contact>, removedSlot: Int) {
        val sorted = points.values.sortedBy { it.slot }
        if (sorted.isEmpty()) return
        val action = if (sorted.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            val index = sorted.indexOfFirst { it.slot == removedSlot }
            MotionEvent.ACTION_POINTER_UP or (max(index, 0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        injectMotion(points, action)
    }

    private fun injectUpAll(points: LinkedHashMap<Int, Contact>) {
        if (points.isEmpty()) return
        // Prototype cleanup: collapse any remaining synthetic gesture with CANCEL.
        injectMotion(points, MotionEvent.ACTION_CANCEL)
    }

    private fun injectMotion(points: Map<Int, Contact>, action: Int): Boolean {
        if (points.isEmpty()) return true
        val sorted = points.values.sortedBy { it.slot }
        val props = Array(sorted.size) { MotionEvent.PointerProperties() }
        val coords = Array(sorted.size) { MotionEvent.PointerCoords() }
        sorted.forEachIndexed { index, c ->
            props[index].id = c.slot
            props[index].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[index].x = c.rawX.toFloat() * displayWidth.toFloat() / (rawMaxX + 1).toFloat()
            coords[index].y = c.rawY.toFloat() * displayHeight.toFloat() / (rawMaxY + 1).toFloat()
            coords[index].pressure = 1f
            coords[index].size = 0.05f
            coords[index].touchMajor = max(1, c.major).toFloat()
            coords[index].touchMinor = max(1, c.minor).toFloat()
        }
        val now = SystemClock.uptimeMillis()
        if (downTime == 0L) downTime = now
        val event = MotionEvent.obtain(
            downTime,
            now,
            action,
            sorted.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            6,
            0,
            SOURCE_TOUCHSCREEN,
            0,
        )
        val masked = action and MotionEvent.ACTION_MASK
        val shouldLog = masked != MotionEvent.ACTION_MOVE || frame % 20L == 0L
        if (shouldLog) {
            val name = when (masked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> masked.toString()
            }
            val ids = sorted.joinToString(",") { it.slot.toString() }
            log.println("OUT frame=$frame action=$name pointers=${sorted.size} ids=$ids")
        }
        return try {
            injector.inject(event, INJECT_WAIT_FOR_RESULT)
        } finally {
            event.recycle()
        }
    }
}
