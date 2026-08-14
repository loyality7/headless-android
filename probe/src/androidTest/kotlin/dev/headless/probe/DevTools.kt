package dev.headless.probe

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Everything the probe needs to reach the WebView DevTools endpoint from inside
 * the app: socket discovery, the HTTP discovery endpoints, an RFC 6455 client
 * over the socket streams, and a minimal CDP session on top.
 *
 * Deliberately synchronous and single-threaded. This is a measurement harness,
 * not the library.
 */

/** Names the endpoint may be published under, in the order worth trying. */
object SocketDiscovery {

    fun candidates(): List<String> = buildList {
        add("webview_devtools_remote_${Process.myPid()}")
        add("webview_devtools_remote")
        addAll(fromProcNetUnix())
    }.distinct()

    /** Last resort. A diagnostic interface, never a contract — see research notes. */
    fun fromProcNetUnix(): List<String> = try {
        File("/proc/net/unix").readLines()
            .map { it.substringAfterLast(' ') }
            .filter { it.startsWith("@webview_devtools_remote") }
            .map { it.removePrefix("@") }
    } catch (e: Exception) {
        emptyList()
    }

    fun connect(name: String): LocalSocket = LocalSocket().apply {
        connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
    }

    /** First name that connects, with the name it was reached by. */
    fun connectAny(): Pair<String, LocalSocket> {
        val failures = mutableListOf<String>()
        for (name in candidates()) {
            try {
                return name to connect(name)
            } catch (e: Exception) {
                failures += "$name: ${e.message}"
            }
        }
        error("no devtools socket reachable. tried: $failures")
    }
}

/** One-shot HTTP/1.1 request over the socket. The server speaks plain HTTP before any upgrade. */
object DevToolsHttp {

    fun get(name: String, path: String): String {
        SocketDiscovery.connect(name).use { socket ->
            socket.outputStream.write(
                ("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toByteArray()
            )
            socket.outputStream.flush()
            val raw = socket.inputStream.readBytes().toString(Charsets.UTF_8)
            val split = raw.indexOf("\r\n\r\n")
            require(split >= 0) { "malformed HTTP response: ${raw.take(200)}" }
            return raw.substring(split + 4)
        }
    }

    /** Page targets, newest first. */
    fun targets(name: String): List<JSONObject> {
        val body = get(name, "/json").trim()
        // Some builds chunk the response; tolerate a leading chunk-size line.
        val json = body.substring(body.indexOf('['))
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun version(name: String): JSONObject {
        val body = get(name, "/json/version").trim()
        return JSONObject(body.substring(body.indexOf('{')))
    }
}

/**
 * Minimal RFC 6455 client. Client frames are masked, server frames are not.
 * Handles fragmentation, ping/pong and close; nothing else is needed for CDP.
 */
class WebSocket private constructor(
    private val socket: LocalSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val random = SecureRandom()

        /** Connects and performs the upgrade handshake. [path] is the debugger URL's path. */
        fun connect(socketName: String, path: String): WebSocket {
            val socket = SocketDiscovery.connect(socketName)
            val out = socket.outputStream
            val input = BufferedInputStream(socket.inputStream)

            val key = ByteArray(16).also { random.nextBytes(it) }
                .let { Base64.encodeToString(it, Base64.NO_WRAP) }

            out.write(
                buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: localhost\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }.toByteArray()
            )
            out.flush()

            val headers = readHeaders(input)
            check(headers.first().contains("101")) { "upgrade refused: ${headers.first()}" }

            val expected = MessageDigest.getInstance("SHA-1")
                .digest((key + GUID).toByteArray())
                .let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val accept = headers.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
                ?.substringAfter(':')?.trim()
            check(accept == expected) { "bad Sec-WebSocket-Accept: $accept" }

            return WebSocket(socket, input, out)
        }

        private fun readHeaders(input: InputStream): List<String> {
            val buffer = ByteArrayOutputStream()
            var state = 0
            while (state < 4) {
                val b = input.read()
                check(b >= 0) { "socket closed during handshake" }
                buffer.write(b)
                state = when {
                    b == '\r'.code && state % 2 == 0 -> state + 1
                    b == '\n'.code && state % 2 == 1 -> state + 1
                    else -> 0
                }
            }
            return buffer.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        }
    }

    fun sendText(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream()
        frame.write(0x81) // FIN + text
        val mask = ByteArray(4).also { random.nextBytes(it) }
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(0x80 or 126)
                frame.write(payload.size ushr 8)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write((payload.size.toLong() ushr shift).toInt() and 0xFF)
            }
        }
        frame.write(mask)
        frame.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        synchronized(output) {
            output.write(frame.toByteArray())
            output.flush()
        }
    }

    /** Next complete text message. Control frames are answered and skipped. */
    fun receiveText(): String {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = readByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val b1 = readByte()
            check(b1 and 0x80 == 0) { "server must not mask" }
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte().toLong() shl 8) or readByte().toLong())
            } else if (length == 127L) {
                length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or readByte().toLong() }
            }
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                check(n > 0) { "socket closed mid-frame" }
                read += n
            }

            when (opcode) {
                0x9 -> { writeControl(0x8A, payload); continue }   // ping -> pong
                0xA -> continue                                     // pong
                0x8 -> error("server closed the connection")        // close
                else -> {
                    message.write(payload)
                    if (fin) return message.toString("UTF-8")
                }
            }
        }
    }

    private fun writeControl(header: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val frame = ByteArrayOutputStream()
        frame.write(header)
        frame.write(0x80 or payload.size)
        frame.write(mask)
        frame.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        synchronized(output) {
            output.write(frame.toByteArray())
            output.flush()
        }
    }

    private fun readByte(): Int = input.read().also { check(it >= 0) { "socket closed" } }

    override fun close() {
        runCatching { writeControl(0x88, ByteArray(0)) }
        runCatching { socket.close() }
    }
}

/** Command/response correlation over one WebSocket. Events are collected, not dispatched. */
class CdpSession(private val ws: WebSocket) : AutoCloseable {

    private var nextId = 0
    val events = mutableListOf<JSONObject>()

    /** Sends [method] and returns the matching response. Events seen on the way are kept. */
    fun send(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = ++nextId
        ws.sendText(
            JSONObject().put("id", id).put("method", method).put("params", params).toString()
        )
        while (true) {
            val message = JSONObject(ws.receiveText())
            if (message.optInt("id", -1) == id) return message
            events += message
        }
    }

    /** Convenience: evaluate an expression and return its primitive value as a string. */
    fun evaluate(expression: String): String {
        val response = send(
            "Runtime.evaluate",
            JSONObject().put("expression", expression).put("returnByValue", true)
        )
        response.optJSONObject("error")?.let { error("Runtime.evaluate failed: $it") }
        return response.getJSONObject("result").getJSONObject("result").opt("value").toString()
    }

    /** True when the method exists on this build. "Method not found" is the signal we probe for. */
    fun supports(method: String, params: JSONObject = JSONObject()): Boolean {
        val response = send(method, params)
        val error = response.optJSONObject("error") ?: return true
        return error.optInt("code") != -32601
    }

    override fun close() = ws.close()
}
