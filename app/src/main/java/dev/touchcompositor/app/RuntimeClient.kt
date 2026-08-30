package dev.touchcompositor.app

import java.io.BufferedReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RuntimeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 28183,
) : AutoCloseable {
    interface Listener {
        fun onConnected(ready: String)
        fun onDisconnected(message: String)
        fun onEvent(line: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var isConnected: Boolean = false
        private set

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val responses = LinkedBlockingQueue<String>()
    private var readerThread: Thread? = null

    @Synchronized
    fun connect(timeoutMs: Int = 1200): String {
        if (isConnected) return "READY"
        closeInternal(false)
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        val reader = s.getInputStream().bufferedReader()
        val out = PrintWriter(s.getOutputStream().bufferedWriter(), true)
        val ready = reader.readLine() ?: error("runtime closed before READY")
        require(ready.startsWith("READY ")) { "unexpected runtime response: $ready" }
        socket = s
        writer = out
        isConnected = true
        readerThread = Thread({ readLoop(reader) }, "touch-runtime-ui-reader").apply {
            isDaemon = true
            start()
        }
        listener?.onConnected(ready)
        return ready
    }

    @Synchronized
    fun request(command: String, timeoutMs: Long = 3000): String {
        check(isConnected) { "runtime is offline" }
        responses.clear()
        val out = writer ?: error("runtime writer missing")
        out.println(command)
        check(!out.checkError()) { "failed to send command" }
        val line = responses.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("runtime response timeout")
        if (line.startsWith("ERR ")) error(line.removePrefix("ERR "))
        require(line.startsWith("OK ")) { "unexpected response: $line" }
        return line.removePrefix("OK ")
    }

    private fun readLoop(reader: BufferedReader) {
        var reason = "runtime disconnected"
        try {
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("EVENT ") -> listener?.onEvent(line.removePrefix("EVENT "))
                    line == "BYE" -> { reason = "runtime stopped"; break }
                    else -> responses.offer(line)
                }
            }
        } catch (t: Throwable) {
            reason = t.message ?: t.javaClass.simpleName
        } finally {
            closeInternal(false)
            listener?.onDisconnected(reason)
        }
    }

    override fun close() = closeInternal(true)

    @Synchronized
    private fun closeInternal(notify: Boolean) {
        val wasConnected = isConnected
        isConnected = false
        runCatching { socket?.close() }
        socket = null
        writer = null
        responses.clear()
        if (notify && wasConnected) listener?.onDisconnected("closed")
    }
}
