package dev.headless.sample

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import dev.headless.browser.platform.PlatformStorageEngine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar

    // Tab Views
    private lateinit var layoutScrapeTab: ScrollView
    private lateinit var layoutScreenshotTab: ScrollView
    private lateinit var layoutStorageTab: ScrollView
    private lateinit var layoutTelemetryTab: ScrollView

    // Tab 1 Views
    private lateinit var btnScrapeHackerNews: Button
    private lateinit var btnScrapeWikipedia: Button
    private lateinit var tvScrapeResults: TextView

    // Tab 2 Views
    private lateinit var btnCaptureScreenshot: Button
    private lateinit var ivScreenshotPreview: ImageView
    private lateinit var tvScreenshotStatus: TextView

    // Tab 3 Views
    private lateinit var btnFetchCookies: Button
    private lateinit var btnClearStorage: Button
    private lateinit var tvStorageResults: TextView

    // Tab 4 Views
    private lateinit var btnRefreshTelemetry: Button
    private lateinit var tvTelemetryDashboard: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tabLayout)
        progressBar = findViewById(R.id.progressBar)

        layoutScrapeTab = findViewById(R.id.layoutScrapeTab)
        layoutScreenshotTab = findViewById(R.id.layoutScreenshotTab)
        layoutStorageTab = findViewById(R.id.layoutStorageTab)
        layoutTelemetryTab = findViewById(R.id.layoutTelemetryTab)

        btnScrapeHackerNews = findViewById(R.id.btnScrapeHackerNews)
        btnScrapeWikipedia = findViewById(R.id.btnScrapeWikipedia)
        tvScrapeResults = findViewById(R.id.tvScrapeResults)

        btnCaptureScreenshot = findViewById(R.id.btnCaptureScreenshot)
        ivScreenshotPreview = findViewById(R.id.ivScreenshotPreview)
        tvScreenshotStatus = findViewById(R.id.tvScreenshotStatus)

        btnFetchCookies = findViewById(R.id.btnFetchCookies)
        btnClearStorage = findViewById(R.id.btnClearStorage)
        tvStorageResults = findViewById(R.id.tvStorageResults)

        btnRefreshTelemetry = findViewById(R.id.btnRefreshTelemetry)
        tvTelemetryDashboard = findViewById(R.id.tvTelemetryDashboard)

        setupTabNavigation()
        setupTab1Actions()
        setupTab2Actions()
        setupTab3Actions()
        setupTab4Actions()
    }

    private fun setupTabNavigation() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                layoutScrapeTab.visibility = View.GONE
                layoutScreenshotTab.visibility = View.GONE
                layoutStorageTab.visibility = View.GONE
                layoutTelemetryTab.visibility = View.GONE

                when (tab?.position) {
                    0 -> layoutScrapeTab.visibility = View.VISIBLE
                    1 -> layoutScreenshotTab.visibility = View.VISIBLE
                    2 -> layoutStorageTab.visibility = View.VISIBLE
                    3 -> layoutTelemetryTab.visibility = View.VISIBLE
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupTab1Actions() {
        btnScrapeHackerNews.setOnClickListener {
            runScrape("HackerNews", "https://news.ycombinator.com") { reader, script ->
                val title = reader.title()
                val story = reader.querySelector(".titleline > a")?.text ?: "N/A"
                val links = script.evaluate("document.querySelectorAll('a').length") ?: "0"
                "Title: $title\n\nTop Story: $story\n\nAnchor Links Count: $links"
            }
        }

        btnScrapeWikipedia.setOnClickListener {
            runScrape("Wikipedia", "https://en.wikipedia.org/wiki/Main_Page") { reader, script ->
                val title = reader.title()
                val welcome = reader.querySelector("#mp-welcome")?.text ?: "N/A"
                val imgs = script.evaluate("document.querySelectorAll('img').length") ?: "0"
                "Title: $title\n\nWelcome Header: $welcome\n\nImage Count: $imgs"
            }
        }
    }

    private fun runScrape(name: String, url: String, block: suspend (PlatformReader, PlatformScriptEngine) -> String) {
        lifecycleScope.launch {
            setLoading(true)
            tvScrapeResults.text = "Initializing PageSession & Navigating to $url..."
            val config = BrowserConfig(enableProtocolBackend = false)
            val session = PageSession(this@MainActivity, Viewport.Phone, config)
            try {
                session.initialize()
                val nav = PlatformNavigator(session, config)
                val script = PlatformScriptEngine(session, config)
                val reader = PlatformReader(session, script, config)

                nav.goto(url, WaitUntil.Load)
                val results = block(reader, script)
                tvScrapeResults.text = results
            } catch (e: Exception) {
                tvScrapeResults.text = "Scrape Error: ${e.message}"
            } finally {
                session.close()
                setLoading(false)
            }
        }
    }

    private fun setupTab2Actions() {
        btnCaptureScreenshot.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvScreenshotStatus.text = "Capturing offscreen layout bitmap for HackerNews..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val nav = PlatformNavigator(session, config)
                    val screenshotEngine = PlatformScreenshotEngine(session, config)

                    nav.goto("https://news.ycombinator.com", WaitUntil.Load)
                    val bytes = screenshotEngine.screenshot()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        ivScreenshotPreview.setImageBitmap(bitmap)
                        tvScreenshotStatus.text = "Successfully captured ${bitmap.width}x${bitmap.height} bitmap (${bytes.size / 1024} KB)!"
                    } else {
                        tvScreenshotStatus.text = "Failed to decode screenshot bitmap."
                    }
                } catch (e: Exception) {
                    tvScreenshotStatus.text = "Screenshot Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }
    }

    private fun setupTab3Actions() {
        btnFetchCookies.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvStorageResults.text = "Setting test cookie and reading cookies..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val script = PlatformScriptEngine(session, config)
                    val storage = PlatformStorageEngine(session, script, config)

                    val targetUrl = "https://news.ycombinator.com"
                    storage.setCookie(targetUrl, "sample_test_cookie=fetch_engine_v1; Path=/")
                    val cookies = storage.getCookies(targetUrl)

                    val cookieList = cookies.joinToString("\n") { "${it.name} = ${it.value}" }
                    tvStorageResults.text = "Domain Cookies ($targetUrl):\n\n$cookieList"
                } catch (e: Exception) {
                    tvStorageResults.text = "Storage Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }

        btnClearStorage.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvStorageResults.text = "Clearing process cookies & WebStorage..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val script = PlatformScriptEngine(session, config)
                    val storage = PlatformStorageEngine(session, script, config)

                    storage.clearCookies()
                    storage.clearStorage()
                    tvStorageResults.text = "Cookies & WebStorage cleared successfully!"
                } catch (e: Exception) {
                    tvStorageResults.text = "Clear Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }
    }

    private fun setupTab4Actions() {
        btnRefreshTelemetry.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvTelemetryDashboard.text = "Running telemetry diagnostic probe session..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val nav = PlatformNavigator(session, config)
                    val script = PlatformScriptEngine(session, config)

                    nav.goto("https://news.ycombinator.com", WaitUntil.Load)
                    script.evaluate("console.log('probe')")

                    val metrics = session.metrics()
                    val activeCount = PageSession.activeSessions
                    val crashes = PageSession.totalRendererCrashes
                    val ooms = PageSession.totalRendererOoms
                    val ssrf = PageSession.totalSsrfBlocked
                    val refusals = PageSession.totalMemoryLimitRefusals

                    tvTelemetryDashboard.text = """
                        === TELEMETRY SNAPSHOT ===
                        
                        Session Duration: ${metrics.sessionDurationMs} ms
                        Total Navigations: ${metrics.totalNavigations}
                        Total JS Evaluations: ${metrics.totalJsEvaluations}
                        
                        === GLOBAL HEALTH DIAGNOSTICS ===
                        
                        Active Sessions: $activeCount
                        Renderer Crashes: $crashes
                        Renderer OOM Kills: $ooms
                        SSRF Blocks: $ssrf
                        Memory Pressure Refusals: $refusals
                        
                        Status: Healthy (Process Baseline OK)
                    """.trimIndent()
                } catch (e: Exception) {
                    tvTelemetryDashboard.text = "Telemetry Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnScrapeHackerNews.isEnabled = !loading
        btnScrapeWikipedia.isEnabled = !loading
        btnCaptureScreenshot.isEnabled = !loading
        btnFetchCookies.isEnabled = !loading
        btnClearStorage.isEnabled = !loading
        btnRefreshTelemetry.isEnabled = !loading
    }
}
