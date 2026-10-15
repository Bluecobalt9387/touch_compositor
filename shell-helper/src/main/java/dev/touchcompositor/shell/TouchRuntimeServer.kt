package dev.touchcompositor.shell

import dev.touchcompositor.input.PhysicalUiRouter
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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed-command control plane for [LiveTouchEngine]. */
class TouchRuntimeServer(
    private val engine: LiveTouchEngine,
    private val baseDisplayWidth: Int,
    private val baseDisplayHeight: Int,
    initialRotation: Int,
    private val owner: String = "standalone",
) {
    companion object {
        const val DEFAULT_PORT = 28183
    }

    private var recordingName: String? = null
    @Volatile private var currentDisplayWidth = if (initialRotation == 90 || initialRotation == 270) baseDisplayHeight else baseDisplayWidth
    @Volatile private var currentDisplayHeight = if (initialRotation == 90 || initialRotation == 270) baseDisplayWidth else baseDisplayHeight
    @Volatile private var currentRotation = initialRotation
    @Volatile private var playbackThread: Thread? = null
    private val playbackBusy = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private val shuttingDown = AtomicBoolean(false)
    private var displayWatcher: DisplayStateWatcher? = null
    private val sockets = CopyOnWriteArraySet<Socket>()
    private val uiOwnerLock = Any()
    @Volatile private var uiOwner: PrintWriter? = null
    private val clients = CopyOnWriteArraySet<PrintWriter>()
    @Volatile private var triggerRegionsSnapshot: List<LiveTouchEngine.TriggerRegion> = emptyList()

    init {
        engine.uiEventListener = { event ->
            broadcastEvent("UI_TOUCH ${event.target} ${event.action} ${event.x} ${event.y}")
        }
        engine.triggerEventListener = { event ->
            broadcastEvent("TRIGGER_${event.action.name} ${event.id}")
        }
    }

    fun runStdio() {
        engine.start()
        startDisplayWatcher()
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
        try {
            // Bind before grabbing the touchscreen: port conflicts must leave input alone.
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                serverSocket = server
                engine.start()
                startDisplayWatcher()
                println("TCP_READY host=127.0.0.1 port=$port")
                while (!shuttingDown.get()) {
                    val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                    sockets += socket
                    Thread({ serveSocket(socket) }, "touch-runtime-client").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        } finally {
            serverSocket = null
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            closeEngine(PrintWriter(System.out, true))
        }
    }

    fun shutdown() {
        shuttingDown.set(true)
        runCatching { serverSocket?.close() }
    }

    private fun serveSocket(socket: Socket) {
        socket.use {
            it.tcpNoDelay = true
            val out = PrintWriter(it.getOutputStream().bufferedWriter(), true)
            clients += out
            try {
                ready(out)
                serve(it.getInputStream().bufferedReader(), out)
            } finally {
                synchronized(uiOwnerLock) {
                    if (uiOwner === out) { uiOwner = null; engine.setUiRegions(emptyList()) }
                }
                clients -= out
                sockets -= socket
            }
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
            "DISPLAY_STATE" -> {
                require(a.size == 1) { "DISPLAY_STATE" }
                ok(out, "DISPLAY ${currentDisplayWidth} ${currentDisplayHeight} ${currentRotation}")
            }
            "DOWN", "MOVE", "UP", "DOWN_N", "MOVE_N", "UP_N" -> {
                require(a.size in 4..10) { "${a[0]} <id> <x> <y> [pressure size touchMajor touchMinor toolMajor toolMinor]" }
                val command = a[0].uppercase()
                val id = a[1].toInt()
                var x = a[2].toFloat()
                var y = a[3].toFloat()
                if (command.endsWith("_N")) {
                    require(x in 0f..1f && y in 0f..1f) { "normalized coordinates must be in [0,1]" }
                    val screen = engine.normalizedToScreen(x, y)
                    x = screen.x
                    y = screen.y
                }
                val attrs = parseAttributes(a.drop(4))
                when (command.removeSuffix("_N")) {
                    "DOWN" -> engine.down(id, x, y, attrs)
                    "MOVE" -> engine.move(id, x, y, attrs)
                    "UP" -> engine.up(id, x, y, attrs)
                }
                ok(out, "$command id=$id")
            }
            "RELEASE_ALL" -> {
                engine.cancelAll()
                ok(out, "RELEASED")
            }
            "UI_SET" -> {
                val count = a.getOrNull(1)?.toIntOrNull() ?: error("UI_SET <count> [id left top right bottom ...]")
                require(count in 0..128 && a.size == 2 + count * 5)
                val regions = (0 until count).map { index ->
                    val b = 2 + index * 5
                    require(a[b].matches(Regex("[A-Za-z0-9_-]{1,80}")))
                    val v = (1..4).map { a[b + it].toFloat().also { f -> require(f.isFinite()) } }
                    require(v[0] <= v[2] && v[1] <= v[3])
                    PhysicalUiRouter.Region(a[b], v[0], v[1], v[2], v[3])
                }
                require(regions.map { it.id }.distinct().size == regions.size)
                synchronized(uiOwnerLock) { uiOwner = out; engine.setUiRegions(regions) }
                ok(out, "UI_REGIONS $count")
            }
            "TRIGGER_SET" -> {
                require(a.size >= 2) { "TRIGGER_SET <count> [id left top right bottom ...]" }
                val count = a[1].toInt().also { require(it in 0..64) }
                require(a.size == 2 + count * 5) { "TRIGGER_SET count/arguments mismatch" }
                val regions = (0 until count).map { index ->
                    val base = 2 + index * 5
                    LiveTouchEngine.TriggerRegion(
                        id = a[base],
                        left = a[base + 1].toFloat(), top = a[base + 2].toFloat(),
                        right = a[base + 3].toFloat(), bottom = a[base + 4].toFloat(),
                    )
                }
                triggerRegionsSnapshot = regions
                engine.setTriggerRegions(regions)
                ok(out, "TRIGGERS ${regions.size}")
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
                // v2 recordings store normalized logical-screen coordinates.
                engine.startRecording(RecordingMetadata(currentDisplayWidth, currentDisplayHeight, currentRotation))
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
                check(playbackBusy.compareAndSet(false, true)) { "playback already active" }
                val name = sanitizeName(a[1])
                val speed = (a.getOrNull(2)?.toDouble() ?: 1.0).also { require(it in 0.1..8.0) }
                val repeat = (a.getOrNull(3)?.toInt() ?: 1).also { require(it in 1..10_000) }
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
                    } finally {
                        playbackBusy.set(false)
                    }
                }, "touch-compositor-player").apply { isDaemon = true }
                playbackThread = thread
                thread.start()
                ok(out, "PLAYING name=$name speed=$speed repeat=$repeat")
            }
            "PLAY_SYNC" -> {
                require(a.size in 2..4) { "PLAY_SYNC <name> [speed] [repeat]" }
                check(playbackBusy.compareAndSet(false, true)) { "playback already active" }
                val name = sanitizeName(a[1])
                val speed = (a.getOrNull(2)?.toDouble() ?: 1.0).also { require(it in 0.1..8.0) }
                val repeat = (a.getOrNull(3)?.toInt() ?: 1).also { require(it in 1..10_000) }
                try {
                    val recording = FileInputStream(recordingFile(name)).use { TouchRecordingCodec.read(it) }
                    val result = engine.play(
                        recording,
                        PlaybackTarget(currentDisplayWidth, currentDisplayHeight, currentRotation),
                        PlaybackOptions(speed = speed, repeatCount = repeat),
                    )
                    ok(out, "PLAYBACK_DONE name=$name repeats=${result.completedRepeats} stopped=${result.stopped} frames=${result.emittedFrames}")
                } finally {
                    playbackBusy.set(false)
                }
            }
            "PAUSE" -> { engine.pausePlayback(); ok(out, "PAUSED") }
            "RESUME" -> { engine.resumePlayback(); ok(out, "RESUMED") }
            "STOP_PLAYBACK" -> { engine.stopPlayback(); ok(out, "STOPPING_PLAYBACK") }
            "SHUTDOWN" -> {
                ok(out, "SHUTTING_DOWN")
                shutdown()
                return false
            }
            else -> error("unknown command ${a[0]}")
        }
        return true
    }

    private fun startDisplayWatcher() {
        displayWatcher?.close()
        displayWatcher = DisplayStateWatcher { width, height, rotation ->
            runCatching {
                engine.updateDisplay(width, height, rotation)
                currentDisplayWidth = width
                currentDisplayHeight = height
                currentRotation = rotation
                // Trigger rectangles are screen-pixel geometry from the overlay.
                // A rotation invalidates them until the app measures the new windows.
                triggerRegionsSnapshot = emptyList()
                engine.setTriggerRegions(emptyList())
                broadcastEvent("DISPLAY_CHANGED $width $height $rotation")
            }.onFailure { t ->
                System.err.println("DISPLAY_UPDATE_ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
        }.also { it.start() }
    }

    private fun ready(out: PrintWriter) {
        // Never acquire the engine lock while holding a writer lock: physical
        // UI events broadcast while the engine lock is held.
        val line = "READY touch-runtime-v1 build=${RuntimeBuild.BUILD} owner=$owner ${engine.status()}"
        synchronized(out) { out.println(line) }
    }

    private fun broadcastEvent(message: String) {
        clients.forEach { out -> synchronized(out) { out.println("EVENT $message") } }
    }

    private fun closeEngine(out: PrintWriter) {
        if (!shuttingDown.compareAndSet(false, true) && !shuttingDown.get()) return
        displayWatcher?.close()
        displayWatcher = null
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
        ?.filter { file ->
            file.isFile &&
                file.name.matches(Regex("touch_compositor_[A-Za-z0-9_-]{1,64}\\.tcr")) &&
                FileInputStream(file).use { TouchRecordingCodec.isSupported(it) }
        }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun ok(out: PrintWriter, message: String) = synchronized(out) { out.println("OK $message") }
}
