package dev.touchcompositor.shell

import dev.touchcompositor.input.PlaybackOptions
import dev.touchcompositor.input.PlaybackTarget
import dev.touchcompositor.input.RecordingMetadata
import dev.touchcompositor.input.TouchAttributes
import dev.touchcompositor.input.TouchRecordingCodec
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed-command control plane for [LiveTouchEngine]. */
class TouchRuntimeServer(
    private val engine: LiveTouchEngine,
    private val baseDisplayWidth: Int,
    private val baseDisplayHeight: Int,
    initialRotation: Int,
) {
    companion object {
        const val DEFAULT_PORT = 28183
    }

    private var recordingName: String? = null
    @Volatile private var currentDisplayWidth = if (initialRotation == 90 || initialRotation == 270) baseDisplayHeight else baseDisplayWidth
    @Volatile private var currentDisplayHeight = if (initialRotation == 90 || initialRotation == 270) baseDisplayWidth else baseDisplayHeight
    @Volatile private var currentRotation = initialRotation
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var serverSocket: ServerSocket? = null
    private val shuttingDown = AtomicBoolean(false)

    fun runStdio() {
        engine.start()
        val out = PrintWriter(System.out, true)
        ready(out)
        try {
            serve(System.`in`.bufferedReader(), out)
        } finally {
            closeEngine(out)
        }
    }

    /** Localhost-only UI transport. One command stream may stay connected indefinitely. */
    fun runTcp(port: Int = DEFAULT_PORT) {
        require(port in 1024..65535)
        engine.start()
        ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            serverSocket = server
            println("TCP_READY host=127.0.0.1 port=$port")
            System.out.flush()
            while (!shuttingDown.get()) {
                val socket = try { server.accept() } catch (_: Throwable) { break }
                Thread({ serveSocket(socket) }, "touch-runtime-client").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        serverSocket = null
        closeEngine(PrintWriter(System.out, true))
    }

    private fun serveSocket(socket: Socket) {
        socket.use {
            it.tcpNoDelay = true
            val out = PrintWriter(it.getOutputStream().bufferedWriter(), true)
            ready(out)
            serve(it.getInputStream().bufferedReader(), out)
        }
    }

    private fun serve(input: BufferedReader, out: PrintWriter) {
        while (!shuttingDown.get()) {
            val line = input.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                if (!handle(trimmed, out)) break
            } catch (t: Throwable) {
                val msg = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').replace('\r', ' ')
                out.println("ERR ${t.javaClass.simpleName} $msg")
            }
        }
    }

    private fun handle(line: String, out: PrintWriter): Boolean {
        val a = line.split(Regex("\\s+"))
        when (a[0].uppercase()) {
            "PING" -> ok(out, "PONG")
            "STATUS" -> ok(out, engine.status())
            "CAPS" -> with(engine.capabilities) {
                ok(out, "read=$canReadPhysicalTouch grab=$canGrabPhysicalTouch inject=$canInjectTouch compose=$canComposePhysicalAndSynthetic max=$maxPointers pressure=$hasHardwarePressure")
            }
            "DISPLAY" -> {
                require(a.size == 4) { "DISPLAY <width> <height> <rotation>" }
                val width = a[1].toInt()
                val height = a[2].toInt()
                val rotation = a[3].toInt()
                require(rotation in setOf(0, 90, 180, 270)) { "rotation must be 0/90/180/270" }
                engine.updateDisplay(width, height, rotation)
                currentDisplayWidth = width
                currentDisplayHeight = height
                currentRotation = rotation
                ok(out, "DISPLAY ${width}x${height} rotation=$rotation")
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
                ok(out, "${a[0].uppercase()} id=$id")
            }
            "RELEASE_ALL" -> {
                engine.cancelAll()
                ok(out, "RELEASED")
            }
            "RECORD_EXCLUDE_CLEAR" -> {
                engine.setRecordingExclusions(emptyList())
                ok(out, "RECORD_EXCLUSIONS 0")
            }
            "RECORD_EXCLUDE" -> {
                require(a.size >= 5 && (a.size - 1) % 4 == 0) {
                    "RECORD_EXCLUDE <left> <top> <right> <bottom> [...]"
                }
                val rects = a.drop(1).chunked(4).map { v ->
                    LiveTouchEngine.RecordingRect(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
                }
                engine.setRecordingExclusions(rects)
                ok(out, "RECORD_EXCLUSIONS ${rects.size}")
            }
            "RECORD_START" -> {
                require(a.size <= 2) { "RECORD_START [name]" }
                check(recordingName == null) { "recording already active" }
                val name = sanitizeName(a.getOrNull(1) ?: "latest")
                // Record raw physical motion in the device's natural orientation.
                // Playback rotates it into the current target orientation.
                engine.startRecording(RecordingMetadata(baseDisplayWidth, baseDisplayHeight, 0))
                recordingName = name
                ok(out, "RECORDING name=$name")
            }
            "RECORD_STOP" -> {
                require(a.size == 1) { "RECORD_STOP" }
                val name = recordingName ?: error("not recording")
                val recording = engine.stopRecording()
                FileOutputStream(recordingFile(name)).use { TouchRecordingCodec.write(recording, it) }
                recordingName = null
                ok(out, "RECORDED name=$name frames=${recording.frames.size} durationNanos=${recording.durationNanos}")
            }
            "LIST" -> {
                val names = recordingFiles().joinToString(",") { it.name.removePrefix("touch_compositor_").removeSuffix(".tcr") }
                ok(out, "RECORDINGS $names")
            }
            "DELETE" -> {
                require(a.size == 2) { "DELETE <name>" }
                val name = sanitizeName(a[1])
                check(recordingName != name) { "cannot delete active recording" }
                val file = recordingFile(name)
                check(!file.exists() || file.delete()) { "delete failed" }
                ok(out, "DELETED name=$name")
            }
            "RENAME" -> {
                require(a.size == 3) { "RENAME <old> <new>" }
                val oldName = sanitizeName(a[1])
                val newName = sanitizeName(a[2])
                check(recordingName != oldName) { "cannot rename active recording" }
                val oldFile = recordingFile(oldName)
                val newFile = recordingFile(newName)
                check(oldFile.isFile) { "recording not found" }
                check(!newFile.exists()) { "target recording already exists" }
                check(oldFile.renameTo(newFile)) { "rename failed" }
                ok(out, "RENAMED old=$oldName new=$newName")
            }
            "INFO" -> {
                require(a.size == 2) { "INFO <name>" }
                val name = sanitizeName(a[1])
                val recording = FileInputStream(recordingFile(name)).use { TouchRecordingCodec.read(it) }
                ok(out, "RECORDING name=$name frames=${recording.frames.size} durationNanos=${recording.durationNanos}")
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
                            PlaybackTarget(currentDisplayWidth, currentDisplayHeight, currentRotation),
                            PlaybackOptions(speed = speed, repeatCount = repeat),
                        )
                        out.println("EVENT PLAYBACK_DONE name=$name repeats=${result.completedRepeats} stopped=${result.stopped} frames=${result.emittedFrames}")
                    } catch (t: Throwable) {
                        out.println("EVENT PLAYBACK_ERROR name=$name type=${t.javaClass.simpleName} message=${(t.message ?: "").replace(' ', '_')}")
                    }
                }, "touch-compositor-player").apply { isDaemon = true }
                playbackThread = thread
                thread.start()
                ok(out, "PLAYING name=$name speed=$speed repeat=$repeat")
            }
            "PAUSE" -> { engine.pausePlayback(); ok(out, "PAUSED") }
            "RESUME" -> { engine.resumePlayback(); ok(out, "RESUMED") }
            "STOP_PLAYBACK" -> { engine.stopPlayback(); ok(out, "STOPPING_PLAYBACK") }
            "SHUTDOWN" -> {
                ok(out, "SHUTTING_DOWN")
                shuttingDown.set(true)
                runCatching { serverSocket?.close() }
                return false
            }
            else -> error("unknown command ${a[0]}")
        }
        return true
    }

    private fun ready(out: PrintWriter) = out.println("READY touch-runtime-v1 ${engine.status()}")

    private fun closeEngine(out: PrintWriter) {
        if (!shuttingDown.compareAndSet(false, true) && !shuttingDown.get()) return
        engine.stopPlayback()
        runCatching { playbackThread?.join(1000) }
        engine.close()
        out.println("BYE")
    }

    private fun parseAttributes(values: List<String>): TouchAttributes {
        val defaults = TouchAttributes()
        fun f(index: Int, default: Float) = values.getOrNull(index)?.toFloat() ?: default
        val touchMajor = f(2, defaults.touchMajor)
        val touchMinor = f(3, defaults.touchMinor)
        return TouchAttributes(
            pressure = f(0, defaults.pressure), size = f(1, defaults.size),
            touchMajor = touchMajor, touchMinor = touchMinor,
            toolMajor = f(4, touchMajor), toolMinor = f(5, touchMinor),
        )
    }

    private fun sanitizeName(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid recording name" }
        return name
    }

    private fun recordingFile(name: String): File = File("/data/local/tmp", "touch_compositor_$name.tcr")

    private fun recordingFiles(): List<File> = File("/data/local/tmp").listFiles()
        ?.filter { it.isFile && it.name.matches(Regex("touch_compositor_[A-Za-z0-9_-]{1,64}\\.tcr")) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun ok(out: PrintWriter, message: String) = out.println("OK $message")
}
