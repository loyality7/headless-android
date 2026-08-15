package dev.headless.browser.protocol

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class WebSocketClientTest {

    @Test
    fun computeAcceptKeyValidatesRFC6455SampleKey() {
        // RFC 6455 Section 1.3 Official Test Vector
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

        val acceptKey = WebSocketClient.computeAcceptKey(clientKey)
        assertEquals(expectedAccept, acceptKey)
    }

    @Test
    fun upgradeHandshakeValidatesAcceptKeyAndRejectsMalformedStatus() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)

        // Mock Server Handshake Responder with invalid 400 response
        val serverThread = Thread {
            val buffer = ByteArray(1024)
            serverIn.read(buffer)
            val badResponse = "HTTP/1.1 400 Bad Request\r\n\r\n"
            serverOut.write(badResponse.toByteArray())
            serverOut.flush()
        }
        serverThread.start()

        val ex = assertThrows(BrowserException::class.java) {
            runBlocking { client.connect() }
        }

        assertEquals(ErrorCode.PROTOCOL_ERROR, ex.code)
        assertTrue("Message should state status 400 failure", ex.message?.contains("400") == true)
        assertFalse(client.isConnected)
    }

    @Test
    fun fragmentedMessageReassembly() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)

        val serverThread = Thread {
            // Read handshake
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

            // Frame 1: Text opcode (0x01), fin = false (0x01), unmasked payload "Hello "
            val frame1 = byteArrayOf(0x01.toByte(), 0x06.toByte()) + "Hello ".toByteArray()
            serverOut.write(frame1)

            // Frame 2: Continuation opcode (0x00), fin = true (0x80), unmasked payload "World!"
            val frame2 = byteArrayOf(0x80.toByte(), 0x06.toByte()) + "World!".toByteArray()
            serverOut.write(frame2)
            serverOut.flush()
        }
        serverThread.start()

        client.connect()

        val msg = withTimeout(3000) {
            client.textMessages.first()
        }

        assertEquals("Hello World!", msg)
        client.close()
    }

    @Test
    fun cleanCloseHandshakeTerminatesReadLoopAndCallback() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        var closeCalled = false
        val client = WebSocketClient(clientIn, clientOut, closeCallback = { closeCalled = true })

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

            // Server sends Close frame (opcode 0x88, fin = true, len = 0)
            serverOut.write(byteArrayOf(0x88.toByte(), 0x00.toByte()))
            serverOut.flush()
        }
        serverThread.start()

        client.connect()
        Thread.sleep(500)

        assertFalse("Client should be disconnected after receiving Close frame", client.isConnected)
        assertTrue("Close callback should be invoked", closeCalled)
    }
}
