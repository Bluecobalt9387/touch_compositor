package dev.touchcompositor.shell

import dev.touchcompositor.input.PlaybackOptions
import dev.touchcompositor.input.PlaybackTarget
import dev.touchcompositor.input.RecordingMetadata
import dev.touchcompositor.input.TouchAttributes
import dev.touchcompositor.input.TouchRecordingCodec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Fixed-command control plane for [LiveTouchEngine].
 *
 * The protocol is intentionally narrow and line based so an ADB transport can
 * keep one app_process session open without exposing arbitrary shell execution.
 */
class TouchRuntimeServer(
    private val engine: LiveTouchEngine,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val rotation: Int,
) {
    private var recordingName: String? = null
    @Volatile private var playbackThread: Thread? = null

    fun run() {
        engine.start()
        println("READY touch-runtime-v1 ${engine.status()}")
        System.out.flush()
        try {
            val input = System.`in`.bufferedReader()
            while (true) {
                val line = input.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    if (!handle(trimmed)) break
                } catch (t: Throwable) {
                    val msg = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').replace('\r', ' ')
                    println("ERR ${t.javaClass.simpleName} $msg")
                    System.out.flush()
                }
            }
        } finally {
            engine.stopPlayback()
            runCatching { playbackThread?.join(1000) }
            engine.close()
            println("BYE")
            System.out.flush()
        }
    }

    private fun handle(line: String): Boolean {
        val a = line.split(Regex("\\s+"))
        when (a[0].uppercase()) {
            "PING" -> ok("PONG")
            "STATUS" -> ok(engine.status())
            "CAPS" -> with(engine.capabilities) {
                ok("read=$canReadPhysicalTouch grab=$canGrabPhysicalTouch inject=$canInjectTouch compose=$canComposePhysicalAndSynthetic max=$maxPointers pressure=$hasHardwarePressure")
            }
            "DOWN", "MOVE", "UP" -> {
                require(a.size in 4..10) { "${a[0]} <id> <x> <y> [pressure size touchMajor touchMinor toolMajor toolMinor]" }
                val id = a[1].toInt()
                val x = a[2].toFloat()
                val y = a[3].toFloat()
                val attrs = parseAttributes(a.drop(4))
                when (a[0].uppercase()) {
                    "DOWN" -> engine.down(id, x, y, attrs)
                    "MOVE" -> engine.move(id, x, y, attrs)
                    "UP" -> engine.up(id, x, y, attrs)
                }
                ok("${a[0].uppercase()} id=$id")
            }
            "RELEASE_ALL" -> {
                engine.cancelAll()
                ok("RELEASED")
            }
            "RECORD_START" -> {
                require(a.size <= 2) { "RECORD_START [name]" }
                check(recordingName == null) { "recording already active" }
                val name = sanitizeName(a.getOrNull(1) ?: "latest")
                engine.startRecording(RecordingMetadata(displayWidth, displayHeight, rotation))
                recordingName = name
                ok("RECORDING name=$name")
            }
            "RECORD_STOP" -> {
                require(a.size == 1) { "RECORD_STOP" }
                val name = recordingName ?: error("not recording")
                val recording = engine.stopRecording()
                val path = recordingFile(name)
                FileOutputStream(path).use { TouchRecordingCodec.write(recording, it) }
                recordingName = null
                ok("RECORDED name=$name frames=${recording.frames.size} durationNanos=${recording.durationNanos}")
            }
            "PLAY" -> {
                require(a.size in 2..4) { "PLAY <name> [speed] [repeat]" }
                check(playbackThread?.isAlive != true) { "playback already active" }
                val name = sanitizeName(a[1])
                val speed = a.getOrNull(2)?.toDouble() ?: 1.0
                val repeat = a.getOrNull(3)?.toInt() ?: 1
                val recording = FileInputStream(recordingFile(name)).use { TouchRecordingCodec.read(it) }
                val thread = Thread({
                    try {
                        val result = engine.play(
                            recording,
                            PlaybackTarget(displayWidth, displayHeight, rotation),
                            PlaybackOptions(speed = speed, repeatCount = repeat),
                        )
                        println("EVENT PLAYBACK_DONE name=$name repeats=${result.completedRepeats} stopped=${result.stopped} frames=${result.emittedFrames}")
                    } catch (t: Throwable) {
                        println("EVENT PLAYBACK_ERROR name=$name type=${t.javaClass.simpleName} message=${(t.message ?: "").replace(' ', '_')}")
                    } finally {
                        System.out.flush()
                    }
                }, "touch-compositor-player").apply { isDaemon = true }
                playbackThread = thread
                thread.start()
                ok("PLAYING name=$name speed=$speed repeat=$repeat")
            }
            "PAUSE" -> {
                engine.pausePlayback()
                ok("PAUSED")
            }
            "RESUME" -> {
                engine.resumePlayback()
                ok("RESUMED")
            }
            "STOP_PLAYBACK" -> {
                engine.stopPlayback()
                ok("STOPPING_PLAYBACK")
            }
            "SHUTDOWN" -> {
                ok("SHUTTING_DOWN")
                return false
            }
            else -> error("unknown command ${a[0]}")
        }
        return true
    }

    private fun parseAttributes(values: List<String>): TouchAttributes {
        val defaults = TouchAttributes()
        fun f(index: Int, default: Float) = values.getOrNull(index)?.toFloat() ?: default
        val touchMajor = f(2, defaults.touchMajor)
        val touchMinor = f(3, defaults.touchMinor)
        return TouchAttributes(
            pressure = f(0, defaults.pressure),
            size = f(1, defaults.size),
            touchMajor = touchMajor,
            touchMinor = touchMinor,
            toolMajor = f(4, touchMajor),
            toolMinor = f(5, touchMinor),
        )
    }

    private fun sanitizeName(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid recording name" }
        return name
    }

    private fun recordingFile(name: String): File =
        File("/data/local/tmp", "touch_compositor_$name.tcr")

    private fun ok(message: String) {
        println("OK $message")
        System.out.flush()
    }
}
