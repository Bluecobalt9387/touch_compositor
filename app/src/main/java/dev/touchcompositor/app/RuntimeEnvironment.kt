package dev.touchcompositor.app

import android.content.Context
import android.os.Build
import dev.touchcompositor.adb.TouchAdbController
import dev.touchcompositor.shell.RuntimeBuild
import java.io.File

/** Bridges product setup (ADB pairing) to the localhost touch runtime. */
class RuntimeEnvironment(context: Context) : AutoCloseable {
    data class Ready(
        val readyLine: String,
        val launchedNow: Boolean,
        val adbEndpoint: TouchAdbController.Endpoint?,
    )

    private val appContext = context.applicationContext
    private val adb = TouchAdbController(appContext).also { it.startDiscovery() }

    val hasPairedIdentity: Boolean get() = adb.hasPairedIdentity
    fun discoverySnapshot(): TouchAdbController.DiscoverySnapshot = adb.discoverySnapshot()
    fun savedConnectEndpoint(): TouchAdbController.Endpoint? = adb.savedConnectEndpoint()

    fun pairAndStart(pairingEndpoint: TouchAdbController.Endpoint, code: String): Result<Ready> = runCatching {
        adb.pair(pairingEndpoint, code).getOrThrow()
        // Give adbd a moment to publish/refresh _adb-tls-connect after pairing.
        Thread.sleep(180)
        ensureReady().getOrThrow()
    }

    fun ensureReady(manualConnect: TouchAdbController.Endpoint? = null): Result<Ready> = runCatching {
        checkExistingRuntime()?.let { return@runCatching Ready(it, false, null) }
        check(adb.hasPairedIdentity) { "尚未完成无线调试配对" }

        val endpoint = adb.connect(manualConnect, discoveryTimeoutMs = 5_500).getOrThrow()
        val helper = extractHelperForCurrentDevice()
        adb.launchRuntime(appContext.packageName, helper).getOrThrow()
        val ready = waitForRuntime(4_500)
            ?: error(buildRuntimeStartError())
        Ready(ready, true, endpoint)
    }

    fun restart(): Result<Ready> = runCatching {
        val client = RuntimeClient()
        try {
            client.connect(450)
            runCatching { client.request("SHUTDOWN", 900) }
        } catch (_: Throwable) {
        } finally {
            client.close()
        }
        Thread.sleep(120)
        ensureReady().getOrThrow()
    }

    fun forgetPairing() {
        adb.forgetPairing()
    }

    private fun checkExistingRuntime(): String? {
        val client = RuntimeClient()
        return try {
            val ready = client.connect(450)
            if (isExpectedRuntime(ready)) {
                ready
            } else {
                runCatching { client.request("SHUTDOWN", 900) }
                Thread.sleep(100)
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    private fun waitForRuntime(timeoutMs: Long): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val client = RuntimeClient()
            try {
                val ready = client.connect(350)
                if (isExpectedRuntime(ready)) return ready
                runCatching { client.request("SHUTDOWN", 800) }
            } catch (_: Throwable) {
                // Startup is asynchronous; retry until deadline.
            } finally {
                client.close()
            }
            Thread.sleep(120)
        }
        return null
    }

    private fun isExpectedRuntime(ready: String): Boolean =
        ready.startsWith("READY touch-runtime-v1 ") && ready.contains("build=${RuntimeBuild.BUILD}")

    private fun extractHelperForCurrentDevice(): File {
        val asset = helperAssetName(Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        val target = File(appContext.cacheDir, asset)
        appContext.assets.open(asset).use { input ->
            val bytes = input.readBytes()
            if (!target.isFile || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        }
        return target
    }

    private fun helperAssetName(abi: String): String = when (abi) {
        "arm64-v8a" -> "tc_evgrab_arm64"
        "armeabi-v7a", "armeabi" -> "tc_evgrab_armv7"
        "x86_64" -> "tc_evgrab_x86_64"
        "x86" -> "tc_evgrab_x86"
        else -> error("暂不支持设备架构：${abi.ifBlank { "unknown" }}")
    }

    private fun buildRuntimeStartError(): String {
        val log = adb.runtimeLog().trim().takeLast(700)
        return if (log.isBlank()) "Touch Runtime 启动超时" else "Touch Runtime 启动失败：$log"
    }

    override fun close() = adb.close()
}
