package dev.touchcompositor.shell

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal Linux evdev multitouch reader for shell uid devices readable through group=input. */
class EvdevTouchReader(private val path: String) {
    private val log = PrintWriter(FileOutputStream("/data/local/tmp/touch_compositor_evdev.log", false), true)
    private var rawEvents = 0L

    private fun out(message: String) {
        log.println(message)
        println(message)
    }
    companion object {
        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0x00
        private const val BTN_TOUCH = 0x14a
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
        var x: Int = 0,
        var y: Int = 0,
        var major: Int = 0,
        var minor: Int = 0,
    )

    private val slots = linkedMapOf<Int, Contact>()
    private var currentSlot = 0
    private var btnTouch = false

    fun run() {
        val eventSize = if (System.getProperty("os.arch")?.contains("64") == true) 24 else 16
        out("EVDEV_READY path=$path arch=${System.getProperty("os.arch")} eventSize=$eventSize")
        BufferedInputStream(FileInputStream(path), eventSize * 128).use { input ->
            val buf = ByteArray(eventSize)
            while (true) {
                var off = 0
                while (off < eventSize) {
                    val n = input.read(buf, off, eventSize - off)
                    if (n < 0) return
                    off += n
                }
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                if (eventSize == 24) {
                    bb.long // tv_sec
                    bb.long // tv_usec
                } else {
                    bb.int
                    bb.int
                }
                val type = bb.short.toInt() and 0xffff
                val code = bb.short.toInt() and 0xffff
                val value = bb.int
                rawEvents++
                when (type) {
                    EV_ABS -> when (code) {
                        ABS_MT_SLOT -> currentSlot = value
                        ABS_MT_TRACKING_ID -> {
                            val c = slots.getOrPut(currentSlot) { Contact(currentSlot) }
                            c.trackingId = value
                            if (value < 0) slots.remove(currentSlot)
                        }
                        ABS_MT_POSITION_X -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.x = value
                        ABS_MT_POSITION_Y -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.y = value
                        ABS_MT_TOUCH_MAJOR -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.major = value
                        ABS_MT_TOUCH_MINOR -> slots.getOrPut(currentSlot) { Contact(currentSlot) }.minor = value
                    }
                    EV_KEY -> if (code == BTN_TOUCH) btnTouch = value != 0
                    EV_SYN -> if (code == SYN_REPORT) emitFrame()
                }
            }
        }
    }

    private fun emitFrame() {
        val active = slots.values.filter { it.trackingId >= 0 }
        if (active.isEmpty() && !btnTouch) {
            out("FRAME n=$rawEvents contacts=0")
            return
        }
        val body = active.joinToString(" ") {
            "s=${it.slot},id=${it.trackingId},x=${it.x},y=${it.y},maj=${it.major},min=${it.minor}"
        }
        out("FRAME n=$rawEvents contacts=${active.size} btn=${if (btnTouch) 1 else 0} $body")
    }
}
