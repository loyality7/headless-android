package dev.webdroid

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything that can go wrong, as a closed set.
 *
 * Callers branch on the code. They never parse a message, and nothing outside
 * this set ever reaches them: a browser failure stays a browser failure and does
 * not become an app crash.
 */
public enum class ErrorCode {
    /** A stage exceeded its ceiling. Distinct from [CANCELLED]. */
    TIMEOUT,

    /** The document could not be loaded: transport failure, refused connection, error status. */
    NAVIGATION_FAILED,

    /** The renderer process died. The session is unusable and must be rebuilt. */
    TARGET_CRASHED,

    /** The control endpoint answered with something unusable, or stopped answering. */
    PROTOCOL_ERROR,

    /** A selector matched nothing before its deadline. Distinct from [TIMEOUT]. */
    SELECTOR_NOT_FOUND,

    /** The session is closed. Every call on it fails this way. */
    DETACHED,

    /** Refused rather than risking an out-of-memory kill. */
    MEMORY_LIMIT,

    /** A request or navigation was blocked by caller-configured rules. */
    BLOCKED,

    /** A navigation resolved to an address the host app did not allow. */
    SSRF_BLOCKED,

    /** The calling coroutine was cancelled. Never reported as [TIMEOUT]. */
    CANCELLED,

    /** This device's WebView cannot do what was asked. Reported immediately, never as a timeout. */
    UNSUPPORTED,
}

/**
 * The only exception this library throws.
 *
 * [message] is written for the caller's log, not for ours: it never carries a
 * stack trace, an internal class name or a socket path. The underlying failure
 * is kept as [cause] for debugging and is not part of the message.
 */
public class BrowserException internal constructor(
    public val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String = "BrowserException($code): $message"
}

internal fun browserError(
    code: ErrorCode,
    message: String,
    cause: Throwable? = null,
): BrowserException = BrowserException(code, sanitize(message), cause)

/**
 * Maps a thrown failure onto the closed set. One place, so a new failure path
 * cannot quietly invent a twelfth error code.
 *
 * Cancellation is checked first and rethrown as [ErrorCode.CANCELLED]: a
 * cancelled call is not a timeout, and callers distinguish the two.
 */
internal fun Throwable.toBrowserException(
    context: String,
    default: ErrorCode = ErrorCode.PROTOCOL_ERROR,
): BrowserException = when (this) {
    is BrowserException -> this

    is TimeoutException -> browserError(ErrorCode.TIMEOUT, "$context timed out", this)

    is CancellationException -> browserError(ErrorCode.CANCELLED, "$context was cancelled", this)

    is SocketTimeoutException -> browserError(ErrorCode.TIMEOUT, "$context timed out", this)

    is UnknownHostException -> browserError(ErrorCode.NAVIGATION_FAILED, "$context could not resolve the host", this)

    is IOException -> browserError(ErrorCode.NAVIGATION_FAILED, "$context failed: the connection was lost", this)

    is OutOfMemoryError -> browserError(ErrorCode.MEMORY_LIMIT, "$context ran out of memory", this)

    else -> browserError(default, "$context failed", this)
}

/** Alias kept local so the mapping does not depend on which timeout type a caller used. */
internal typealias TimeoutException = kotlinx.coroutines.TimeoutCancellationException

/**
 * Strips anything that would leak internals into a caller-visible message:
 * stack frames, package-qualified names, and file or socket paths.
 */
private fun sanitize(message: String): String =
    message.lineSequence().first()
        .replace(INTERNAL_NAME, "")
        .replace(PATH, "")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_MESSAGE_LENGTH)

private val INTERNAL_NAME = Regex("""\b(?:[a-z][a-z0-9_]*\.){2,}[A-Za-z][A-Za-z0-9_$]*""")
private val PATH = Regex("""[@/][\w./-]{2,}""")
private val WHITESPACE = Regex("""\s+""")
private const val MAX_MESSAGE_LENGTH = 200
