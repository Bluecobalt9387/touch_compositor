package dev.touchcompositor.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Product-facing ADB bootstrapper. It exposes only operations Touch Compositor needs. */
class TouchAdbController(context: Context) : AutoCloseable {
    data class Endpoint(val host: String, val port: Int) {
        init { require(host.isNotBlank()); require(port in 1..65535) }
        override fun toString(): String = if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
    }

    data class DiscoverySnapshot(
        val pairing: Endpoint?,
        val connect: Endpoint?,
    )

    data class TouchscreenProbe(
        val devicePath: String,
        val rawMinX: Int,
        val rawMaxX: Int,
        val rawMinY: Int,
        val rawMaxY: Int,
        val hasPressure: Boolean,
    )

    data class DeviceProbe(
        val touchscreen: TouchscreenProbe,
        /** Natural-orientation logical display size from wm size. */
        val displayWidth: Int,
        val displayHeight: Int,
        val rotation: Int,
        val abi: String,
        val apkPath: String,
    )

    data class RuntimeLaunch(
        val probe: DeviceProbe,
        val remoteHelperPath: String,
        val logPath: String,
    )

    companion object {
        private const val PREFS = "touch_adb"
        private const val KEY_PAIRED = "paired"
        private const val KEY_CONNECT_HOST = "connect_host"
        private const val KEY_CONNECT_PORT = "connect_port"
        private const val HELPER_REMOTE = "/data/local/tmp/tc_evgrab"
        private const val RUNTIME_LOG = "/data/local/tmp/touch_compositor_runtime.log"
        private const val RUNTIME_PORT = 28183

        fun parseEndpoint(text: String): Endpoint? {
            val value = text.trim()
            if (value.isEmpty()) return null
            if (value.startsWith('[')) {
                val close = value.indexOf(']')
                if (close <= 1 || close + 2 > value.length || value.getOrNull(close + 1) != ':') return null
                val host = value.substring(1, close)
                val port = value.substring(close + 2).toIntOrNull() ?: return null
                return runCatching { Endpoint(host, port) }.getOrNull()
            }
            val split = value.lastIndexOf(':')
            if (split <= 0 || split == value.lastIndex) return null
            val host = value.substring(0, split)
            if (':' in host) return null // IPv6 must use [address]:port to remain unambiguous.
            val port = value.substring(split + 1).toIntOrNull() ?: return null
            return runCatching { Endpoint(host, port) }.getOrNull()
        }

        internal fun parseTouchscreen(getevent: String): TouchscreenProbe? {
            data class Candidate(
                var path: String = "",
                var minX: Int? = null,
                var maxX: Int? = null,
                var minY: Int? = null,
                var maxY: Int? = null,
                var slot: Boolean = false,
                var tracking: Boolean = false,
                var direct: Boolean = false,
                var pressure: Boolean = false,
            )
            val candidates = mutableListOf<Candidate>()
            var current: Candidate? = null
            val add = Regex("^add device \\d+: (\\S+)")
            val range = Regex("min (-?\\d+), max (-?\\d+)")
            for (line in getevent.lineSequence()) {
                val match = add.find(line.trimStart())
                if (match != null) {
                    current = Candidate(path = match.groupValues[1]).also(candidates::add)
                    continue
                }
                val c = current ?: continue
                when {
                    "ABS_MT_SLOT" in line -> c.slot = true
                    "ABS_MT_TRACKING_ID" in line -> c.tracking = true
                    "ABS_MT_POSITION_X" in line -> range.find(line)?.let { m ->
                        c.minX = m.groupValues[1].toIntOrNull(); c.maxX = m.groupValues[2].toIntOrNull()
                    }
                    "ABS_MT_POSITION_Y" in line -> range.find(line)?.let { m ->
                        c.minY = m.groupValues[1].toIntOrNull(); c.maxY = m.groupValues[2].toIntOrNull()
                    }
                    "ABS_MT_PRESSURE" in line -> c.pressure = true
                    "INPUT_PROP_DIRECT" in line -> c.direct = true
                }
            }
            return candidates
                .filter {
                    it.path.startsWith("/dev/input/event") && it.slot && it.tracking && it.direct &&
                        it.minX != null && it.maxX != null && it.minY != null && it.maxY != null &&
                        it.maxX!! > it.minX!! && it.maxY!! > it.minY!!
                }
                .maxByOrNull { (it.maxX!! - it.minX!!).toLong() * (it.maxY!! - it.minY!!).toLong() }
                ?.let { TouchscreenProbe(it.path, it.minX!!, it.maxX!!, it.minY!!, it.maxY!!, it.pressure) }
        }

        internal fun parseWmSize(output: String): Pair<Int, Int>? {
            val regex = Regex("(?:Override|Physical) size: (\\d+)x(\\d+)")
            val values = regex.findAll(output).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            return values.lastOrNull()
        }

        internal fun parseRotation(viewport: String): Int {
            val orientation = Regex("orientation\\s*=\\s*([0-3])").find(viewport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return when (orientation and 3) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var manager = PersistentAdbConnectionManager(appContext)
    private val pairingRef = AtomicReference<Endpoint?>(null)
    private val connectRef = AtomicReference<Endpoint?>(null)
    @Volatile private var runtimeStream: AdbStream? = null
    @Volatile private var runtimeDrainThread: Thread? = null
    private val runtimeOutputLock = Any()
    private val runtimeOutput = StringBuilder()

    private val pairingMdns = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
        val address = host?.hostAddress
        pairingRef.set(if (!address.isNullOrBlank() && port > 0) Endpoint(address, port) else null)
    }
    private val connectMdns = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
        val address = host?.hostAddress
        connectRef.set(if (!address.isNullOrBlank() && port > 0) Endpoint(address, port) else null)
    }

    val hasPairedIdentity: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)

    fun startDiscovery() {
        runCatching { pairingMdns.start() }
        runCatching { connectMdns.start() }
    }

    fun discoverySnapshot(): DiscoverySnapshot = DiscoverySnapshot(pairingRef.get(), connectRef.get())

    fun savedConnectEndpoint(): Endpoint? {
        val host = prefs.getString(KEY_CONNECT_HOST, null) ?: return null
        val port = prefs.getInt(KEY_CONNECT_PORT, -1)
        return if (port in 1..65535) Endpoint(host, port) else null
    }

    @Synchronized
    fun pair(endpoint: Endpoint, code: String): Result<Unit> = runCatching {
        require(code.matches(Regex("\\d{6}"))) { "配对码必须是 6 位数字" }
        manager.disconnectQuietly()
        var lastError: Throwable? = null
        var paired = false
        for (host in listOf("127.0.0.1", endpoint.host).distinct()) {
            paired = runCatching { manager.pair(host, endpoint.port, code) }
                .onFailure { lastError = it }
                .getOrDefault(false)
            if (paired) break
        }
        if (!paired) throw lastError ?: IllegalStateException("配对失败")
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
    }

    /** Connect to the current wireless-debugging transport. */
    @Synchronized
    fun connect(endpoint: Endpoint? = null, discoveryTimeoutMs: Long = 4500): Result<Endpoint?> = runCatching {
        if (manager.isConnected) return@runCatching endpoint ?: savedConnectEndpoint() ?: connectRef.get()
        val candidates = buildList {
            endpoint?.let(::add)
            connectRef.get()?.let(::add)
            savedConnectEndpoint()?.let(::add)
        }.distinct()

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val hosts = listOf("127.0.0.1", candidate.host).distinct()
            for (host in hosts) {
                manager.disconnectQuietly()
                val ok = runCatching { manager.connect(host, candidate.port) }
                    .onFailure { lastError = it }
                    .getOrDefault(false)
                if (ok || manager.isConnected) {
                    val connected = Endpoint(host, candidate.port)
                    saveConnectEndpoint(connected)
                    return@runCatching connected
                }
            }
        }

        manager.disconnectQuietly()
        val auto = runCatching { manager.connectTls(appContext, discoveryTimeoutMs) }
            .onFailure { lastError = it }
            .getOrDefault(false)
        if (auto || manager.isConnected) {
            val discovered = connectRef.get()
            if (discovered != null) saveConnectEndpoint(discovered)
            return@runCatching discovered
        }
        throw lastError ?: IllegalStateException("没有发现可连接的无线调试端口")
    }

    fun isConnected(): Boolean = manager.isConnected

    /** Shell access is intentionally private to this product bootstrapper. */
    fun probeDevice(packageName: String): Result<DeviceProbe> = runCatching {
        check(manager.isConnected) { "ADB 未连接" }
        val touchscreen = parseTouchscreen(shell("getevent -pl 2>/dev/null"))
            ?: error("未找到可用的多点触摸设备")
        val (displayWidth, displayHeight) = parseWmSize(shell("wm size"))
            ?: error("无法读取屏幕尺寸")
        val viewport = shell("dumpsys input | grep -m 1 'Viewport INTERNAL'")
        val rotation = parseRotation(viewport)
        val abi = shell("getprop ro.product.cpu.abi").trim().ifBlank { error("无法读取 CPU ABI") }
        val apkPath = shell("pm path ${safeToken(packageName)} | head -n 1")
            .lineSequence().firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")?.trim()
            ?: error("无法定位应用 APK")
        DeviceProbe(touchscreen, displayWidth, displayHeight, rotation, abi, apkPath)
    }

    fun launchRuntime(packageName: String, helperFile: File): Result<RuntimeLaunch> = runCatching {
        check(manager.isConnected) { "ADB 未连接" }
        require(helperFile.isFile && helperFile.length() > 0) { "helper 文件无效" }
        val probe = probeDevice(packageName).getOrThrow()

        writeRemoteFile(HELPER_REMOTE, helperFile.readBytes())
        shell("chmod 755 $HELPER_REMOTE")
        val remoteSize = shell("wc -c < $HELPER_REMOTE").trim().toLongOrNull()
        check(remoteSize == helperFile.length()) { "helper 部署不完整" }

        closeRuntimeStream()
        stopStaleRuntimeProcesses()
        val t = probe.touchscreen
        val command = buildString {
            append("CLASSPATH=").append(shellQuote(probe.apkPath)).append(' ')
            append("/system/bin/app_process /system/bin ")
            append("dev.touchcompositor.shell.ShellInputService runtime-tcp ")
            append(shellQuote(t.devicePath)).append(' ')
            append(t.rawMinX).append(' ').append(t.rawMaxX).append(' ')
            append(t.rawMinY).append(' ').append(t.rawMaxY).append(' ')
            append(probe.displayWidth).append(' ').append(probe.displayHeight).append(' ')
            append(HELPER_REMOTE).append(' ').append(probe.rotation).append(' ').append(RUNTIME_PORT).append(' ')
            append(t.hasPressure)
            append(" 2>&1")
        }
        synchronized(runtimeOutputLock) { runtimeOutput.setLength(0) }
        val stream = manager.openStream("shell:$command")
        runtimeStream = stream
        runtimeDrainThread = Thread({ drainRuntime(stream) }, "touch-runtime-adb").apply {
            isDaemon = true
            start()
        }
        RuntimeLaunch(probe, HELPER_REMOTE, RUNTIME_LOG)
    }

    fun runtimeLog(): String = synchronized(runtimeOutputLock) { runtimeOutput.toString().takeLast(4_000) }

    fun forgetPairing() {
        closeRuntimeStream()
        manager.disconnectQuietly()
        runCatching { pairingMdns.stop() }
        runCatching { connectMdns.stop() }
        prefs.edit().clear().apply()
        PersistentAdbConnectionManager.clearStoredIdentity(appContext)
        manager = PersistentAdbConnectionManager(appContext)
        pairingRef.set(null); connectRef.set(null)
        startDiscovery()
    }

    override fun close() {
        closeRuntimeStream()
        runCatching { pairingMdns.stop() }
        runCatching { connectMdns.stop() }
        manager.disconnectQuietly()
    }

    private fun drainRuntime(stream: AdbStream) {
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)
        try {
            while (runtimeStream === stream) {
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    if (stream.isClosed && error.message?.startsWith("Stream closed") == true) -1
                    else throw error
                }
                if (count < 0) break
                if (count > 0) {
                    val text = String(buffer, 0, count, StandardCharsets.UTF_8)
                    synchronized(runtimeOutputLock) {
                        runtimeOutput.append(text)
                        if (runtimeOutput.length > 16_000) runtimeOutput.delete(0, runtimeOutput.length - 12_000)
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(runtimeOutputLock) {
                runtimeOutput.append("\nADB_RUNTIME_STREAM_ERROR: ").append(error.message ?: error.javaClass.simpleName)
            }
        } finally {
            if (runtimeStream === stream) runtimeStream = null
            runCatching { stream.close() }
        }
    }

    private fun closeRuntimeStream() {
        val stream = runtimeStream
        runtimeStream = null
        runCatching { stream?.close() }
        runtimeDrainThread?.interrupt()
        runtimeDrainThread = null
    }

    private fun saveConnectEndpoint(endpoint: Endpoint) {
        prefs.edit().putString(KEY_CONNECT_HOST, endpoint.host).putInt(KEY_CONNECT_PORT, endpoint.port).apply()
    }

    private fun shell(command: String): String {
        val stream = manager.openStream("shell:$command")
        return stream.useAdb { adb ->
            val input = adb.openInputStream()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    // libadb-android 3.1.1 reports the remote shell's normal
                    // CLSE as IOException("Stream closed.") instead of EOF.
                    // Treat that specific state as end-of-stream; transport
                    // failures with any other error still propagate.
                    if (adb.isClosed && error.message?.startsWith("Stream closed") == true) -1
                    else throw error
                }
                if (count < 0) break
                if (count > 0) out.write(buffer, 0, count)
            }
            out.toByteArray().toString(StandardCharsets.UTF_8)
        }
    }

    /** Push a small runtime asset using the ADB SYNC protocol.
     *
     * A shell `cat > file` upload is not sufficient here: closing an ADB shell
     * stream does not guarantee the remote writer has released the inode, so an
     * immediate exec may fail with ETXTBSY. SYNC's OKAY is the completion
     * boundary we need.
     */
    private fun writeRemoteFile(path: String, bytes: ByteArray) {
        require(path.startsWith("/data/local/tmp/"))
        val stream = manager.openStream("sync:")
        stream.useAdb { adb ->
            val out = adb.openOutputStream()
            val input = adb.openInputStream()
            val send = "$path,33188".toByteArray(StandardCharsets.UTF_8) // regular file, 0644
            writeSyncPacket(out, "SEND", send)
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(64 * 1024, bytes.size - offset)
                writeSyncHeader(out, "DATA", count)
                out.write(bytes, offset, count)
                offset += count
            }
            writeSyncHeader(out, "DONE", (System.currentTimeMillis() / 1000L).toInt())
            out.flush()

            val status = readFully(input, 4).toString(StandardCharsets.US_ASCII)
            when (status) {
                "OKAY" -> Unit
                "FAIL" -> {
                    val length = readLeInt(input)
                    val message = if (length in 0..65536) readFully(input, length).toString(StandardCharsets.UTF_8) else "invalid failure response"
                    error("helper push failed: $message")
                }
                else -> error("helper push failed: unexpected SYNC status $status")
            }
        }
    }

    private fun writeSyncPacket(out: java.io.OutputStream, id: String, payload: ByteArray) {
        writeSyncHeader(out, id, payload.size)
        out.write(payload)
    }

    private fun writeSyncHeader(out: java.io.OutputStream, id: String, value: Int) {
        require(id.length == 4)
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun readLeInt(input: java.io.InputStream): Int =
        ByteBuffer.wrap(readFully(input, 4)).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readFully(input: java.io.InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = try { input.read(out, offset, length - offset) } catch (error: IOException) {
                throw IOException("unexpected end of ADB SYNC response", error)
            }
            if (count < 0) throw IOException("unexpected end of ADB SYNC response")
            offset += count
        }
        return out
    }

    private fun stopStaleRuntimeProcesses() {
        val ps = shell("ps -A -o PID,ARGS")
        val pids = ps.lineSequence().mapNotNull { line ->
            if (!line.contains("dev.touchcompositor.shell.ShellInputService runtime-tcp")) return@mapNotNull null
            line.trim().substringBefore(' ').toIntOrNull()
        }.toList()
        pids.forEach { pid -> runCatching { shell("kill $pid") } }
        if (pids.isNotEmpty()) Thread.sleep(120)
    }

    private inline fun <T> AdbStream.useAdb(block: (AdbStream) -> T): T = try {
        block(this)
    } finally {
        runCatching { close() }
    }

    private fun PersistentAdbConnectionManager.disconnectQuietly() {
        runCatching { if (isConnected) disconnect() }
    }

    private fun safeToken(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe token" }
        return value
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
