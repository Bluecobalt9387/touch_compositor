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
            "runtime" -> {
                val a = args.drop(1)
                require(a.size >= 7) { "runtime <event> <rawMaxX> <rawMaxY> <displayW> <displayH> <rotation> <grabHelper> [hasPressure]" }
                val engine = LiveTouchEngine(
                    devicePath = a[0],
                    rawMaxX = a[1].toInt(),
                    rawMaxY = a[2].toInt(),
                    displayWidth = a[3].toInt(),
                    displayHeight = a[4].toInt(),
                    rotation = a[5].toInt(),
                    grabHelperPath = a[6],
                    hasHardwarePressure = a.getOrNull(7)?.toBooleanStrictOrNull() ?: false,
                )
                TouchRuntimeServer(engine, a[3].toInt(), a[4].toInt(), a[5].toInt()).run()
            }
            "record-test" -> {
                val a = args.drop(1)
                require(a.size >= 7) { "record-test <event> <rawMaxX> <rawMaxY> <displayW> <displayH> <rotation> <seconds>" }
                val out = "/data/local/tmp/touch_compositor_recording.tcr"
                val recording = EvdevRecorder(
                    devicePath = a[0],
                    rawMaxX = a[1].toInt(),
                    rawMaxY = a[2].toInt(),
                    displayWidth = a[3].toInt(),
                    displayHeight = a[4].toInt(),
                    rotation = a[5].toInt(),
                ).recordTo(out, a[6].toInt())
                println("RECORDING_SAVED path=$out frames=${recording.frames.size} durationNanos=${recording.durationNanos}")
            }
            "play-test-recording" -> {
                val a = args.drop(1)
                require(a.size >= 3) { "play-test-recording <displayW> <displayH> <rotation> [speed] [repeat]" }
                val result = RecordingPlayer.play(
                    inputPath = "/data/local/tmp/touch_compositor_recording.tcr",
                    displayWidth = a[0].toInt(),
                    displayHeight = a[1].toInt(),
                    rotation = a[2].toInt(),
                    speed = a.getOrNull(3)?.toDoubleOrNull() ?: 1.0,
                    repeatCount = a.getOrNull(4)?.toIntOrNull() ?: 1,
                )
                println("PLAYBACK_DONE repeats=${result.completedRepeats} stopped=${result.stopped} frames=${result.emittedFrames}")
            }
            "replay", "replay-demo" -> {
                val a = args.drop(1)
                require(a.size >= 6) { "replay <event> <rawMaxX> <rawMaxY> <displayW> <displayH> <grabHelper>" }
                EvdevReplayCompositor(
                    devicePath = a[0],
                    rawMaxX = a[1].toInt(),
                    rawMaxY = a[2].toInt(),
                    displayWidth = a[3].toInt(),
                    displayHeight = a[4].toInt(),
                    grabHelperPath = a[5],
                    demoVirtualTap = args.firstOrNull() == "replay-demo",
                ).run()
            }
            else -> println("touch_compositor shell helper: ready")
        }
    }

    private val injector by lazy { InputInjectorCompat() }

    private fun monitorInputManager(): Any =
        InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)
            ?: error("InputManager.getInstance() unavailable for InputMonitor on this build")

    private fun inject(event: MotionEvent): Boolean = injector.inject(event, 1)

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
            val manager = monitorInputManager()
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
