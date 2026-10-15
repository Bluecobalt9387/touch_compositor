package dev.touchcompositor.app

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeClientTest {
    @Test fun missingGreetingTimesOutAndClosesSocket() {
        ServerSocket(0).use { server ->
            val closed = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    if (socket.getInputStream().read() == -1) closed.countDown()
                }
            }.apply { start() }
            RuntimeClient(port = server.localPort).use { client ->
                assertThrows(SocketTimeoutException::class.java) { client.connect(100) }
                assertFalse(client.isConnected)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
            peer.join(2000)
        }
    }

    @Test fun responseTimeoutInvalidatesConnection() {
        ServerSocket(0).use { server ->
            val closed = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("READY test\n".toByteArray())
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine() // Accept PING without replying.
                    if (reader.readLine() == null) closed.countDown()
                }
            }.apply { start() }
            RuntimeClient(port = server.localPort).use { client ->
                client.connect()
                assertThrows(IllegalStateException::class.java) { client.request("PING", 100) }
                assertFalse(client.isConnected)
                assertThrows(IllegalStateException::class.java) { client.request("STATUS", 100) }
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
            peer.join(2000)
        }
    }
}
