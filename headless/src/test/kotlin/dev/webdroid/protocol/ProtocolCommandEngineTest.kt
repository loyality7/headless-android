package dev.webdroid.protocol

import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ProtocolCommandEngineTest {

    @Test
    fun typedCommandsPageAndRuntimeDeserialization() = runBlocking {
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

            // Respond to Runtime.evaluate
            val bufferCmd = ByteArray(1024)
            serverIn.read(bufferCmd)
            val resp = """{"id": 1, "result": {"result": {"type": "string", "value": "Test Title"}}}"""
            val frameCmd = byteArrayOf(0x81.toByte(), resp.length.toByte()) + resp.toByteArray()
            serverOut.write(frameCmd)
            serverOut.flush()
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)
        val engine = ProtocolCommandEngine(channel)

        val res = engine.runtimeEvaluate("document.title")
        assertEquals("string", res.type)
        assertEquals("Test Title", res.value)

        channel.close()
    }

    @Test
    fun unboundDomainCommandThrowsUnsupportedError() = runBlocking {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)

        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val client = WebSocketClient(clientIn, clientOut)
        val channel = CdpChannel(client)
        val engine = ProtocolCommandEngine(channel)

        var caught: Throwable? = null
        try {
            engine.executeCommand("Target.getTargets")
        } catch (ex: Throwable) {
            caught = ex
        }

        assertTrue("Exception should be BrowserException", caught is BrowserException)
        assertEquals(ErrorCode.UNSUPPORTED, (caught as? BrowserException)?.code)
        assertTrue("Error should state domain is unbound", caught?.message?.contains("unbound") == true)
    }

    @Test
    fun malformedResponsePayloadRaisesProtocolError() = runBlocking {
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

            // Respond to Runtime.evaluate with missing 'result' object
            val bufferCmd = ByteArray(1024)
            serverIn.read(bufferCmd)
            val resp = """{"id": 1, "result": {}}"""
            val frameCmd = byteArrayOf(0x81.toByte(), resp.length.toByte()) + resp.toByteArray()
            serverOut.write(frameCmd)
            serverOut.flush()
        }
        serverThread.start()

        client.connect()
        val channel = CdpChannel(client)
        val engine = ProtocolCommandEngine(channel)

        var caught: BrowserException? = null
        try {
            engine.runtimeEvaluate("1 + 1")
        } catch (ex: BrowserException) {
            caught = ex
        }

        assertTrue("Exception should be BrowserException", caught != null)
        assertEquals(ErrorCode.PROTOCOL_ERROR, caught?.code)
        channel.close()
    }
}
