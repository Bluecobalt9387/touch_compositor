package dev.touchcompositor.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
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
        val rawMaxX: Int,
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
                var maxX: Int? = null,
                var maxY: Int? = null,
                var slot: Boolean = false,
                var tracking: Boolean = false,
                var direct: Boolean = false,
                var pressure: Boolean = false,
            )
            val candidates = mutableListOf<Candidate>()
            var current: Candidate? = null
            val add = Regex("^add device \\d+: (\\S+)")
            val max = Regex("max (-?\\d+)")
            for (line in getevent.lineSequence()) {
                val match = add.find(line)
                if (match != null) {
                    current = Candidate(path = match.groupValues[1]).also(candidates::add)
                    continue
                }
                val c = current ?: continue
                when {
                    "ABS_MT_SLOT" in line -> c.slot = true
                    "ABS_MT_TRACKING_ID" in line -> c.tracking = true
                    "ABS_MT_POSITION_X" in line -> c.maxX = max.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    "ABS_MT_POSITION_Y" in line -> c.maxY = max.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    "ABS_MT_PRESSURE" in line -> c.pressure = true
                    "INPUT_PROP_DIRECT" in line -> c.direct = true
                }
            }
            return candidates
                .filter { it.path.startsWith("/dev/input/event") && it.slot && it.tracking && it.direct && (it.maxX ?: 0) > 0 && (it.maxY ?: 0) > 0 }
                .maxByOrNull { (it.maxX ?: 0).toLong() * (it.maxY ?: 0).toLong() }
                ?.let { TouchscreenProbe(it.path, it.maxX!!, it.maxY!!, it.pressure) }
        }

        internal fun parseWmSize(output: String): Pair<Int, Int>? {
            val regex = Regex("(?:Override|Physical) size: (\\d+)x(\\d+)")
            val values = regex.findAll(output).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            return values.lastOrNull()
        }

        internal fun parseRotation(viewport: String): Int {
            val orientation = Regex("orientation=(\\d)").find(viewport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return when (orientation and 3) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var manager = PersistentAdbConnectionManager(appContext)
    private val pairingRef = AtomicReference<Endpoint?>(null)
    private val connectRef = AtomicReference<Endpoint?>(null)

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

        stopStaleRuntimeProcesses()
        shell("rm -f $RUNTIME_LOG")
        val t = probe.touchscreen
        val command = buildString {
            append("CLASSPATH=").append(shellQuote(probe.apkPath)).append(' ')
            append("setsid /system/bin/app_process /system/bin ")
            append("dev.touchcompositor.shell.ShellInputService runtime-tcp ")
            append(shellQuote(t.devicePath)).append(' ')
            append(t.rawMaxX).append(' ').append(t.rawMaxY).append(' ')
            append(probe.displayWidth).append(' ').append(probe.displayHeight).append(' ')
            append(HELPER_REMOTE).append(' ').append(probe.rotation).append(' ').append(RUNTIME_PORT).append(' ')
            append(t.hasPressure)
            append(" </dev/null >").append(RUNTIME_LOG).append(" 2>&1 &")
        }
        shell(command)
        RuntimeLaunch(probe, HELPER_REMOTE, RUNTIME_LOG)
    }

    fun runtimeLog(): String = runCatching { shell("cat $RUNTIME_LOG 2>/dev/null") }.getOrDefault("")

    fun forgetPairing() {
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
        runCatching { pairingMdns.stop() }
        runCatching { connectMdns.stop() }
        manager.disconnectQuietly()
    }

    private fun saveConnectEndpoint(endpoint: Endpoint) {
        prefs.edit().putString(KEY_CONNECT_HOST, endpoint.host).putInt(KEY_CONNECT_PORT, endpoint.port).apply()
    }

    private fun shell(command: String): String {
        val stream = manager.openStream("shell:$command")
        return stream.useAdb { adb ->
            adb.openInputStream().use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
        }
    }

    private fun writeRemoteFile(path: String, bytes: ByteArray) {
        require(path.startsWith("/data/local/tmp/"))
        val stream = manager.openStream("shell:cat > ${shellQuote(path)}")
        stream.useAdb { adb ->
            val out = adb.openOutputStream()
            out.write(bytes)
            out.flush()
            // Closing the ADB stream supplies EOF to remote cat.
        }
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
