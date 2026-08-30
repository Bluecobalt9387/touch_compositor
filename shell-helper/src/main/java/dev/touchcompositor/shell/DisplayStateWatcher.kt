package dev.touchcompositor.shell

import android.graphics.Point
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

/** Watches display 0 from the shell process; no overlay/app callback is required. */
class DisplayStateWatcher(
    private val onChanged: (width: Int, height: Int, rotationDegrees: Int) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "touch-display-watcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var last: Triple<Int, Int, Int>? = null
        while (running.get()) {
            try {
                val state = readDisplayState()
                if (state != last) {
                    last = state
                    onChanged(state.first, state.second, state.third)
                }
            } catch (t: Throwable) {
                System.err.println("DISPLAY_WATCH_ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
        }
    }

    private fun readDisplayState(): Triple<Int, Int, Int> {
        val cls = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = cls.getDeclaredMethod("getInstance").invoke(null)
            ?: error("DisplayManagerGlobal unavailable")
        val display = cls.getDeclaredMethod("getRealDisplay", Int::class.javaPrimitiveType)
            .invoke(global, 0) as? Display ?: error("display 0 unavailable")
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val rotation = when (display.rotation) {
            0 -> 0
            1 -> 90
            2 -> 180
            3 -> 270
            else -> error("invalid rotation ${display.rotation}")
        }
        return Triple(size.x, size.y, rotation)
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        thread?.interrupt()
        runCatching { thread?.join(500) }
        thread = null
    }
}
