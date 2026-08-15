package dev.headless.browser.protocol

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class CdpChannelTest {

    @Test(timeout = 10_000)
    fun commandCorrelationWithInterleavedResponses() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)

        // Mock Server responding out-of-order
        val serverThread = Thread {
            val buffer = ByteArray(1024)
            val bytes = serverIn.read(buffer)
            val reqStr = String(buffer, 0, bytes)
            val clientKeyLine = reqStr.lines().first { it.startsWith("Sec-WebSocket-Key:") }
            val clientKey = clientKeyLine.split(":")[1].trim()
            val acceptKey = WebSocketClient.computeAcceptKey(clientKey)

            val handshakeResp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

            serverOut.write(handshakeResp.toByteArray())
            serverOut.flush()

            // Server reads incoming request frame(s)
            val readBuf = ByteArray(4096)
            val n = serverIn.read(readBuf, 0, readBuf.size)
            if (n > 0 && serverIn.available() > 0) {
                serverIn.read(readBuf, n, serverIn.available())
            }

            // Send response for command ID 2 first (unmasked text frame)
            val resp2 = """{"id": 2, "result": {"value": "second"}}"""
            val frame2 = byteArrayOf(0x81.toByte(), resp2.length.toByte()) + resp2.toByteArray()
            serverOut.write(frame2)

            // Send response for command ID 1 second (unmasked text frame)
            val resp1 = """{"id": 1, "result": {"value": "first"}}"""
            val frame1 = byteArrayOf(0x81.toByte(), resp1.length.toByte()) + resp1.toByteArray()
            serverOut.write(frame1)
            serverOut.flush()

            // Keep server thread alive reading until client closes channel
            runCatching {
                val dummy = ByteArray(1024)
                while (serverIn.read(dummy) != -1) {}
            }
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)

        val def1 = async { channel.sendCommand("Test.first") }
        val def2 = async { channel.sendCommand("Test.second") }

        val res1 = def1.await()
        val res2 = def2.await()

        assertEquals("first", res1.getString("value"))
        assertEquals("second", res2.getString("value"))

        channel.close()
    }

    @Test(timeout = 10_000)
    fun slowEventSubscriberDoesNotStallCommands() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)

        val serverThread = Thread {
            val buffer = ByteArray(1024)
            val bytes = serverIn.read(buffer)
            val reqStr = String(buffer, 0, bytes)
            val clientKeyLine = reqStr.lines().first { it.startsWith("Sec-WebSocket-Key:") }
            val clientKey = clientKeyLine.split(":")[1].trim()
            val acceptKey = WebSocketClient.computeAcceptKey(clientKey)

            val handshakeResp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

            serverOut.write(handshakeResp.toByteArray())
            serverOut.flush()

            // Emit 10 event frames without reading command response
            for (i in 1..10) {
                val eventJson = """{"method": "Page.domContentEventFired", "params": {"timestamp": $i}}"""
                val frame = byteArrayOf(0x81.toByte(), eventJson.length.toByte()) + eventJson.toByteArray()
                serverOut.write(frame)
            }

            // Then respond to command ID 1
            val bufferCmd = ByteArray(1024)
            serverIn.read(bufferCmd)
            val resp = """{"id": 1, "result": {"status": "ok"}}"""
            val frameCmd = byteArrayOf(0x81.toByte(), resp.length.toByte()) + resp.toByteArray()
            serverOut.write(frameCmd)
            serverOut.flush()
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)

        // Send command without subscribing to events
        val res = channel.sendCommand("Page.enable")
        assertEquals("ok", res.getString("status"))

        channel.close()
    }

    @Test(timeout = 10_000)
    fun teardownCancelsOutstandingCommandsWithCancelledError() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)

        val serverThread = Thread {
            val buffer = ByteArray(1024)
            val bytes = serverIn.read(buffer)
            val reqStr = String(buffer, 0, bytes)
            val clientKeyLine = reqStr.lines().first { it.startsWith("Sec-WebSocket-Key:") }
            val clientKey = clientKeyLine.split(":")[1].trim()
            val acceptKey = WebSocketClient.computeAcceptKey(clientKey)

            val handshakeResp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

            serverOut.write(handshakeResp.toByteArray())
            serverOut.flush()

            // Keep reading loop active so pipe stream stays open while command is pending
            runCatching {
                while (true) {
                    if (serverIn.read(buffer) == -1) break
                }
            }
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val pendingCmd = scope.async { channel.sendCommand("Page.navigate") }
        Thread.sleep(200)

        // Teardown channel while command is pending
        channel.close()

        val ex = runCatching { pendingCmd.await() }.exceptionOrNull()
        assertTrue("Exception should be BrowserException", ex is BrowserException)
        assertEquals(ErrorCode.CANCELLED, (ex as BrowserException).code)
        assertTrue(channel.isClosed)
    }
}
