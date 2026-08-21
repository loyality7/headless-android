package dev.webdroid.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.browserError
import dev.webdroid.core.PageSession
import dev.webdroid.core.SessionState
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
public class PlatformScreenshotEngine(
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
    public suspend fun screenshot(
        options: ScreenshotOptions = ScreenshotOptions(),
    ): ByteArray = session.runInState(SessionState.Operating) {
        dev.webdroid.core.CapabilityGuard.requireScreenshots(session.capabilities())

        val viewport = session.viewport ?: throw browserError(ErrorCode.UNSUPPORTED, "Screenshot requires viewport")
        val hosted = session.hostedWebView
        val width = hosted.webView.width.let { if (it <= 0) viewport.width else it }
        val height = hosted.webView.height.let { if (it <= 0) viewport.height else it }

        if (width <= 1 || height <= 1) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Screenshot requires a viewport-sized session; current viewport dimensions are ${width}x${height}",
            )
        }

        val deferred = CompletableDeferred<ByteArray>()

        withContext(Dispatchers.Main) {
            hosted.webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
            )
            hosted.webView.layout(0, 0, width, height)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            @Suppress("DEPRECATION")
            val picture = hosted.webView.capturePicture()
            if (picture != null && picture.width > 0 && picture.height > 0) {
                picture.draw(canvas)
            } else {
                hosted.webView.draw(canvas)
            }

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
