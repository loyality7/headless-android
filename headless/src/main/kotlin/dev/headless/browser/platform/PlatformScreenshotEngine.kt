package dev.headless.browser.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

public enum class ScreenshotFormat {
    PNG,
    JPEG,
    WEBP,
}

public data class ScreenshotOptions(
    public val format: ScreenshotFormat = ScreenshotFormat.PNG,
    public val quality: Int = 100,
)

/**
 * Handles view drawing and screenshot capture for the platform backend.
 */
internal class PlatformScreenshotEngine(
    private val session: PageSession,
    private val config: BrowserConfig,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Captures a screenshot of the session's WebView by drawing the view into a bitmap.
     *
     * @param options Format (PNG, JPEG, WEBP) and quality (0-100).
     * @return [ByteArray] containing the encoded screenshot bytes.
     * @throws BrowserException [ErrorCode.UNSUPPORTED] if session is configured with a 1x1 viewport.
     */
    suspend fun screenshot(
        options: ScreenshotOptions = ScreenshotOptions(),
    ): ByteArray = session.runInState(SessionState.Operating) {
        if (session.viewport == null) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Screenshot requires a viewport-sized session; current view is detached 1x1",
            )
        }

        val hosted = session.hostedWebView
        val width = hosted.webView.width.let { if (it <= 0) session.viewport.width else it }
        val height = hosted.webView.height.let { if (it <= 0) session.viewport.height else it }

        if (width <= 1 || height <= 1) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Screenshot requires a viewport-sized session; current viewport dimensions are ${width}x${height}",
            )
        }

        val deferred = CompletableDeferred<ByteArray>()

        withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            hosted.webView.draw(canvas)

            val compressFormat = when (options.format) {
                ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
                ScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ScreenshotFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(compressFormat, options.quality.coerceIn(0, 100), stream)
            val bytes = stream.toByteArray()

            // Deterministic bitmap memory release
            bitmap.recycle()
            deferred.complete(bytes)
        }

        deferred.await()
    }
}
