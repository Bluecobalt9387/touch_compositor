package dev.touchcompositor.shell

import android.hardware.input.InputManager
import android.os.Looper
import android.os.SystemClock
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent

/** Runs from app_process under uid=2000(shell). */
object ShellInputService {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "probe-input-api" -> probeInputApi()
            "monitor" -> monitor(args.drop(1))
            "tap" -> injectTap(args.drop(1))
            "evdev" -> EvdevTouchReader(args.getOrNull(1) ?: "/dev/input/event5").run()
            else -> println("touch_compositor shell helper: ready")
        }
    }

    private fun inputManager(): Any =
        InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)

    private fun inject(event: MotionEvent): Boolean {
        val manager = inputManager()
        val method = InputManager::class.java.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        return method.invoke(manager, event, 1) as Boolean
    }

    private fun injectTap(args: List<String>) {
        val x = args.getOrNull(0)?.toFloatOrNull() ?: error("tap <x> <y> [deviceId]")
        val y = args.getOrNull(1)?.toFloatOrNull() ?: error("tap <x> <y> [deviceId]")
        val deviceId = args.getOrNull(2)?.toIntOrNull() ?: 0
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x, y, 1f, 1f, 0, 1f, 1f, deviceId, 0,
        ).apply { source = 0x1002 }
        val okDown = inject(down)
        down.recycle()
        SystemClock.sleep(35)
        val now = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            downTime, now, MotionEvent.ACTION_UP,
            x, y, 1f, 1f, 0, 1f, 1f, deviceId, 0,
        ).apply { source = 0x1002 }
        val okUp = inject(up)
        up.recycle()
        println("TAP x=$x y=$y deviceId=$deviceId down=$okDown up=$okUp")
    }

    private fun monitor(args: List<String>) {
        val displayId = args.firstOrNull()?.toIntOrNull() ?: 0
        Looper.prepare()
        var monitor: InputMonitor? = null
        try {
            val manager = inputManager()
            val method = InputManager::class.java.getMethod(
                "monitorGestureInput",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            monitor = method.invoke(manager, "touch_compositor", displayId) as InputMonitor
            val inputMonitor = monitor
            val receiver = object : InputEventReceiver(inputMonitor.getInputChannel(), Looper.myLooper()!!) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        if (event is MotionEvent) {
                            println("TOUCH action=${event.actionMasked} device=${event.deviceId} pointers=${event.pointerCount}")
                            System.out.flush()
                        }
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
            Runtime.getRuntime().addShutdownHook(Thread {
                try { receiver.dispose() } catch (_: Throwable) {}
                try { inputMonitor.dispose() } catch (_: Throwable) {}
            })
            println("MONITOR_READY display=$displayId")
            Looper.loop()
        } catch (t: Throwable) {
            System.err.println("MONITOR_ERROR ${t.javaClass.name}: ${t.message}")
            t.printStackTrace(System.err)
            try { monitor?.dispose() } catch (_: Throwable) {}
            kotlin.system.exitProcess(2)
        }
    }

    private fun probeInputApi() {
        val names = listOf(
            "android.hardware.input.InputManager",
            "android.hardware.input.InputManagerGlobal",
            "android.view.InputMonitor",
            "android.view.InputChannel",
            "android.view.InputEventReceiver",
        )
        for (name in names) {
            println("=== $name ===")
            try {
                val cls = Class.forName(name)
                cls.declaredConstructors.sortedBy { it.toString() }.forEach { println("CTOR $it") }
                cls.declaredMethods.sortedBy { it.toString() }.forEach { println("METHOD $it") }
            } catch (t: Throwable) {
                println("ERROR ${t.javaClass.name}: ${t.message}")
            }
        }
    }
}
