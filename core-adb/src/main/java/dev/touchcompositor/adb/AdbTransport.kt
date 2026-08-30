package dev.touchcompositor.adb

/**
 * Narrow ADB capability surface exposed to the app.
 * No arbitrary shell(String) API is intentionally provided here.
 */
interface AdbTransport {
    val state: ConnectionState
    suspend fun pair(endpoint: PairingEndpoint, code: String): Result<Unit>
    suspend fun connect(endpoint: ConnectEndpoint): Result<Unit>
    suspend fun disconnect()
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
data class PairingEndpoint(val host: String, val port: Int)
data class ConnectEndpoint(val host: String, val port: Int)
