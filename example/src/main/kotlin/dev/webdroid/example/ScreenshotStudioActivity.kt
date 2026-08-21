package dev.webdroid.example

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.webdroid.BrowserConfig
import dev.webdroid.Viewport
import dev.webdroid.WaitUntil
import dev.webdroid.core.PageSession
import dev.webdroid.platform.PlatformNavigator
import dev.webdroid.platform.PlatformScreenshotEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

public class ScreenshotStudioActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var btnCaptureHackerNews: Button
    private lateinit var btnCaptureWikipedia: Button
    private lateinit var ivScreenshotPreview: ImageView
    private lateinit var tvScreenshotStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_studio)

        supportActionBar?.title = "Offscreen Screenshot Studio"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        btnCaptureHackerNews = findViewById(R.id.btnCaptureHackerNews)
        btnCaptureWikipedia = findViewById(R.id.btnCaptureWikipedia)
        ivScreenshotPreview = findViewById(R.id.ivScreenshotPreview)
        tvScreenshotStatus = findViewById(R.id.tvScreenshotStatus)

        btnCaptureHackerNews.setOnClickListener { capture("https://news.ycombinator.com") }
        btnCaptureWikipedia.setOnClickListener { capture("https://en.wikipedia.org/wiki/Main_Page") }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun capture(url: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            progressBar.visibility = android.view.View.VISIBLE
            tvScreenshotStatus.text = "Capturing offscreen layout for $url..."
            val config = BrowserConfig(enableProtocolBackend = false)
            val session = PageSession(this@ScreenshotStudioActivity, Viewport.Phone, config)
            try {
                session.initialize()
                val nav = PlatformNavigator(session, config)
                val screenshotEngine = PlatformScreenshotEngine(session, config)

                nav.goto(url, WaitUntil.Load)
                val bytes = screenshotEngine.screenshot()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    ivScreenshotPreview.setImageBitmap(bitmap)
                    tvScreenshotStatus.text = "Captured ${bitmap.width}x${bitmap.height} bitmap (${bytes.size / 1024} KB) for $url"
                } else {
                    tvScreenshotStatus.text = "Failed to decode screenshot bitmap."
                }
            } catch (e: Exception) {
                tvScreenshotStatus.text = "Capture Error: ${e.message}"
            } finally {
                session.close()
                progressBar.visibility = android.view.View.GONE
            }
        }
    }
}
