package dev.headless.browser.protocol

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.experimental.xor

/**
 * Hand-written RFC 6455 WebSocket client operating directly over [InputStream] and [OutputStream] streams.
 */
internal class WebSocketClient(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val closeCallback: (() -> Unit)? = null,
) {
    public companion object {
        const val MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_PAYLOAD_SIZE = 10 * 1024 * 1024 // 10MB hard cap to prevent memory exhaustion

        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA

        fun generateKey(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return java.util.Base64.getEncoder().encodeToString(bytes)
        }

        fun computeAcceptKey(clientKey: String): String {
            val concat = clientKey.trim() + MAGIC_GUID
            val sha1 = MessageDigest.getInstance("SHA-1").digest(concat.toByteArray(StandardCharsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(sha1)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val random = SecureRandom()

    private val _textMessages = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 64)
    val textMessages: SharedFlow<String> = _textMessages.asSharedFlow()

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * Executes the RFC 6455 HTTP upgrade handshake.
     *
     * @param path Target request path (e.g. `/devtools/page/123`).
     * @param host Target host header (e.g. `localhost`).
     * @throws BrowserException [ErrorCode.PROTOCOL_ERROR] if response is malformed or accept key fails.
     */
    suspend fun connect(path: String = "/devtools/page/1", host: String = "localhost"): Unit = withContext(Dispatchers.IO) {
        val clientKey = generateKey()
        val expectedAccept = computeAcceptKey(clientKey)

        val handshakeRequest = "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $clientKey\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"

        try {
            outputStream.write(handshakeRequest.toByteArray(StandardCharsets.UTF_8))
            outputStream.flush()
        } catch (e: Exception) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to send WebSocket handshake request", e)
        }

        val responseHeader = readHttpResponseHeader(inputStream)
        val statusLine = responseHeader.lines().firstOrNull() ?: ""

        if (!statusLine.contains("101")) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "WebSocket handshake failed with status: $statusLine")
        }

        val serverAccept = parseHeader(responseHeader, "Sec-WebSocket-Accept")
        if (serverAccept == null || serverAccept.trim() != expectedAccept.trim()) {
            throw browserError(
                ErrorCode.PROTOCOL_ERROR,
                "WebSocket accept key mismatch! Expected: $expectedAccept, Got: $serverAccept",
            )
        }

        isConnected = true
        startReadLoop()
    }

    private fun readHttpResponseHeader(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val temp = ByteArray(1)
        var matchedEol = 0

        while (matchedEol < 4) {
            val bytesRead = input.read(temp, 0, 1)
            if (bytesRead == -1) break

            val b = temp[0].toInt()
            buffer.write(b)

            if ((matchedEol == 0 || matchedEol == 2) && b == '\r'.code) {
                matchedEol++
            } else if ((matchedEol == 1 || matchedEol == 3) && b == '\n'.code) {
                matchedEol++
            } else {
                matchedEol = if (b == '\r'.code) 1 else 0
            }
        }

        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun parseHeader(headers: String, key: String): String? {
        val line = headers.lines().firstOrNull { it.startsWith(key, ignoreCase = true) } ?: return null
        val parts = line.split(":", limit = 2)
        return if (parts.size == 2) parts[1].trim() else null
    }

    /**
     * Encodes and transmits a text message frame to the server with client masking.
     */
    suspend fun sendText(text: String): Unit = withContext(Dispatchers.IO) {
        if (!isConnected) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "WebSocket is not connected")
        }
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        writeFrame(OPCODE_TEXT, payload, fin = true)
    }

    /**
     * Sends a Close frame (opcode 0x8) and shuts down resources.
     */
    suspend fun close(): Unit = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext
        isConnected = false

        runCatching {
            writeFrame(OPCODE_CLOSE, ByteArray(0), fin = true)
        }
        scope.cancel()
        closeCallback?.invoke()
    }

    private fun writeFrame(opCode: Int, payload: ByteArray, fin: Boolean) {
        val maskKey = ByteArray(4)
        random.nextBytes(maskKey)

        val finBit = if (fin) 0x80 else 0x00
        val firstByte = finBit or (opCode and 0x0F)

        val payloadLen = payload.size
        val maskBit = 0x80

        val headerBuffer = ByteArrayOutputStream()
        headerBuffer.write(firstByte)

        when {
            payloadLen < 126 -> {
                headerBuffer.write(maskBit or payloadLen)
            }
            payloadLen <= 65535 -> {
                headerBuffer.write(maskBit or 126)
                headerBuffer.write((payloadLen shr 8) and 0xFF)
                headerBuffer.write(payloadLen and 0xFF)
            }
            else -> {
                headerBuffer.write(maskBit or 127)
                val buffer = ByteBuffer.allocate(8)
                buffer.putLong(payloadLen.toLong())
                headerBuffer.write(buffer.array())
            }
        }

        headerBuffer.write(maskKey)

        val maskedPayload = ByteArray(payloadLen)
        for (i in 0 until payloadLen) {
            maskedPayload[i] = payload[i] xor maskKey[i % 4]
        }

        outputStream.write(headerBuffer.toByteArray())
        outputStream.write(maskedPayload)
        outputStream.flush()
    }

    private fun startReadLoop() {
        scope.launch {
            val messageBuffer = ByteArrayOutputStream()
            var fragmentedOpcode = 0

            while (isActive && isConnected) {
                try {
                    val firstByte = inputStream.read()
                    if (firstByte == -1) break

                    val fin = (firstByte and 0x80) != 0
                    val opCode = firstByte and 0x0F

                    val secondByte = inputStream.read()
                    if (secondByte == -1) break

                    val isMasked = (secondByte and 0x80) != 0
                    var payloadLen = (secondByte and 0x7F).toLong()

                    if (payloadLen == 126L) {
                        val b1 = inputStream.read()
                        val b2 = inputStream.read()
                        if (b1 == -1 || b2 == -1) break
                        payloadLen = ((b1 and 0xFF) shl 8 or (b2 and 0xFF)).toLong()
                    } else if (payloadLen == 127L) {
                        val lenBytes = ByteArray(8)
                        val read = inputStream.read(lenBytes, 0, 8)
                        if (read < 8) break
                        payloadLen = ByteBuffer.wrap(lenBytes).long
                    }

                    if (payloadLen > MAX_PAYLOAD_SIZE) {
                        throw browserError(
                            ErrorCode.PROTOCOL_ERROR,
                            "Received frame payload size $payloadLen exceeds maximum limit of $MAX_PAYLOAD_SIZE bytes",
                        )
                    }

                    val maskKey = if (isMasked) {
                        val key = ByteArray(4)
                        val read = inputStream.read(key, 0, 4)
                        if (read < 4) break
                        key
                    } else null

                    val payload = ByteArray(payloadLen.toInt())
                    var bytesRead = 0
                    while (bytesRead < payloadLen.toInt()) {
                        val r = inputStream.read(payload, bytesRead, payloadLen.toInt() - bytesRead)
                        if (r == -1) break
                        bytesRead += r
                    }

                    if (isMasked && maskKey != null) {
                        for (i in 0 until payload.size) {
                            payload[i] = payload[i] xor maskKey[i % 4]
                        }
                    }

                    when (opCode) {
                        OPCODE_PING -> {
                            // Respond with Pong (opcode 0xA)
                            writeFrame(OPCODE_PONG, payload, fin = true)
                        }
                        OPCODE_PONG -> {
                            // Ignore pongs
                        }
                        OPCODE_CLOSE -> {
                            isConnected = false
                            break
                        }
                        OPCODE_TEXT, OPCODE_BINARY, OPCODE_CONTINUATION -> {
                            if (opCode != OPCODE_CONTINUATION) {
                                fragmentedOpcode = opCode
                            }
                            messageBuffer.write(payload)

                            if (fin) {
                                val fullMessageBytes = messageBuffer.toByteArray()
                                messageBuffer.reset()
                                if (fragmentedOpcode == OPCODE_TEXT) {
                                    val textMessage = String(fullMessageBytes, StandardCharsets.UTF_8)
                                    _textMessages.emit(textMessage)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    isConnected = false
                    break
                }
            }

            isConnected = false
            closeCallback?.invoke()
        }
    }
}
