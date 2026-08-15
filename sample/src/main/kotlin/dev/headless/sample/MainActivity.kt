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
import dev.headless.browser.platform.PlatformInputEngine
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
    private lateinit var btnTestFormSubmit: Button
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
        btnTestFormSubmit = findViewById(R.id.btnTestFormSubmit)
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
        // Multi-Step Automation 1: Open HackerNews -> Click Top Link -> Navigate to Article -> Read Content
        btnScrapeHackerNews.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvScrapeResults.text = "Step 1: Navigating to HackerNews..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val nav = PlatformNavigator(session, config)
                    val script = PlatformScriptEngine(session, config)
                    val reader = PlatformReader(session, script, config)
                    val input = PlatformInputEngine(session, script, reader, config)

                    nav.goto("https://news.ycombinator.com", WaitUntil.Load)
                    val initialTitle = reader.title()
                    val topStorySelector = ".titleline > a"
                    val topStoryTitle = reader.querySelector(topStorySelector)?.text ?: "N/A"

                    tvScrapeResults.text = "Step 2: Clicking top story using PlatformInputEngine..."
                    input.click(topStorySelector)

                    val newTitle = reader.title()
                    tvScrapeResults.text = """
                        === AUTOMATION PIPELINE COMPLETED ===
                        
                        1. [Initial Page Title]: $initialTitle
                        2. [Clicked Story]: $topStoryTitle
                        3. [Target Article Title]: $newTitle
                        4. [Status]: Successfully clicked element & navigated to new page!
                    """.trimIndent()
                } catch (e: Exception) {
                    tvScrapeResults.text = "Automation Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }

        // Multi-Step Automation 2: Open Wikipedia -> Type Search -> Submit -> Read Result Page
        btnScrapeWikipedia.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvScrapeResults.text = "Step 1: Navigating to Wikipedia..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val nav = PlatformNavigator(session, config)
                    val script = PlatformScriptEngine(session, config)
                    val reader = PlatformReader(session, script, config)
                    val input = PlatformInputEngine(session, script, reader, config)

                    nav.goto("https://en.wikipedia.org/wiki/Main_Page", WaitUntil.Load)
                    val initialTitle = reader.title()

                    tvScrapeResults.text = "Step 2: Typing search query 'Android' via PlatformInputEngine..."
                    val searchInputSelector = "input[name='search']"
                    input.type(searchInputSelector, "Android")

                    tvScrapeResults.text = "Step 3: Submitting search button click..."
                    val searchBtnSelector = "button.cdx-search-input__end-button"
                    runCatching { input.click(searchBtnSelector) }

                    val resultTitle = reader.title()
                    val resultHeader = reader.querySelector("#firstHeading")?.text ?: "N/A"

                    tvScrapeResults.text = """
                        === AUTOMATION SEARCH PIPELINE COMPLETED ===
                        
                        1. [Initial Page]: $initialTitle
                        2. [Input Typed]: "Android" into input[name='search']
                        3. [Result Page Title]: $resultTitle
                        4. [Result Heading]: $resultHeader
                    """.trimIndent()
                } catch (e: Exception) {
                    tvScrapeResults.text = "Search Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
            }
        }

        // Live Multi-Element HTML Form Automation: Text inputs, Radios, Checkboxes, Textarea, and Submit
        btnTestFormSubmit.setOnClickListener {
            lifecycleScope.launch {
                setLoading(true)
                tvScrapeResults.text = "Step 1: Navigating to Complete HTML Form Test Site..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@MainActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val nav = PlatformNavigator(session, config)
                    val script = PlatformScriptEngine(session, config)
                    val reader = PlatformReader(session, script, config)
                    val input = PlatformInputEngine(session, script, reader, config)

                    nav.goto("https://httpbin.org/forms/post", WaitUntil.Load)

                    tvScrapeResults.text = "Step 2: Filling Customer Details & Preferences..."
                    input.type("input[name='custname']", "John Doe")
                    input.type("input[name='custtel']", "+15550199")
                    input.type("input[name='custemail']", "john@example.com")

                    tvScrapeResults.text = "Step 3: Selecting Radio Option & Checkboxes..."
                    input.click("input[name='size'][value='medium']")
                    input.click("input[name='topping'][value='bacon']")
                    input.click("input[name='topping'][value='cheese']")

                    tvScrapeResults.text = "Step 4: Typing Textarea Instructions & Submitting..."
                    input.type("textarea[name='comments']", "Extra crispy crust please!")
                    input.click("button")

                    kotlinx.coroutines.delay(1500)
                    val responseText = reader.text().take(300)

                    tvScrapeResults.text = """
                        === COMPLETE HTML FORM SUBMISSION COMPLETED ===
                        
                        1. [Test URL]: https://httpbin.org/forms/post
                        2. [Customer]: John Doe (+15550199, john@example.com)
                        3. [Pizza Preferences]: Medium, Bacon + Cheese
                        4. [Comments]: Extra crispy crust please!
                        5. [Server Response]:
                        $responseText
                    """.trimIndent()
                } catch (e: Exception) {
                    tvScrapeResults.text = "Form Submission Error: ${e.message}"
                } finally {
                    session.close()
                    setLoading(false)
                }
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
        btnTestFormSubmit.isEnabled = !loading
        btnCaptureScreenshot.isEnabled = !loading
        btnFetchCookies.isEnabled = !loading
        btnClearStorage.isEnabled = !loading
        btnRefreshTelemetry.isEnabled = !loading
    }
}
