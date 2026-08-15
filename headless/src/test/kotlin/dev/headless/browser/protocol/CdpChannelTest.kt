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

    @Test
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

            // Server reads 2 commands, then responds to ID 2 first, then ID 1
            val req1Bytes = ByteArray(1024)
            val r1 = serverIn.read(req1Bytes)
            val req2Bytes = ByteArray(1024)
            val r2 = serverIn.read(req2Bytes)

            // Send response for command ID 2 first (unmasked text frame)
            val resp2 = """{"id": 2, "result": {"value": "second"}}"""
            val frame2 = byteArrayOf(0x81.toByte(), resp2.length.toByte()) + resp2.toByteArray()
            serverOut.write(frame2)

            // Send response for command ID 1 second (unmasked text frame)
            val resp1 = """{"id": 1, "result": {"value": "first"}}"""
            val frame1 = byteArrayOf(0x81.toByte(), resp1.length.toByte()) + resp1.toByteArray()
            serverOut.write(frame1)
            serverOut.flush()
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

    @Test
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

            // Emit 300 event frames without reading command response
            for (i in 1..300) {
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

    @Test
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
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)

        val pendingCmd = async(kotlinx.coroutines.SupervisorJob()) { channel.sendCommand("Page.navigate") }
        Thread.sleep(200)

        // Teardown channel while command is pending
        channel.close()

        val ex = runCatching { pendingCmd.await() }.exceptionOrNull()
        assertTrue("Exception should be BrowserException", ex is BrowserException)
        assertEquals(ErrorCode.CANCELLED, (ex as BrowserException).code)
        assertTrue(channel.isClosed)
    }
}
