package dev.headless.example

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformInputEngine
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

public class ScrapeReadActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var btnHackerNews: Button
    private lateinit var btnWikipedia: Button
    private lateinit var btnLoginForm: Button
    private lateinit var btnPizzaForm: Button
    private lateinit var tvScrapeResults: TextView
    private lateinit var layoutStepsContainer: LinearLayout

    public data class StepScreenshot(
        public val title: String,
        public val bytes: ByteArray
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scrape_read)

        supportActionBar?.title = "Scrape, Input & Screenshot"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        btnHackerNews = findViewById(R.id.btnHackerNews)
        btnWikipedia = findViewById(R.id.btnWikipedia)
        btnLoginForm = findViewById(R.id.btnLoginForm)
        btnPizzaForm = findViewById(R.id.btnPizzaForm)
        tvScrapeResults = findViewById(R.id.tvScrapeResults)
        layoutStepsContainer = findViewById(R.id.layoutStepsContainer)

        setupActions()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupActions() {
        btnHackerNews.setOnClickListener {
            runScrapeAndMultiStepScreenshot("HackerNews", "https://news.ycombinator.com") { reader, _, _, screenshotEngine, steps ->
                steps.add(StepScreenshot("Step 1: Loaded Page", screenshotEngine.screenshot()))
                val title = reader.title()
                val story = reader.querySelector(".titleline > a")?.text ?: "N/A"
                "Title: $title\n\nTop Story: $story"
            }
        }

        btnWikipedia.setOnClickListener {
            runScrapeAndMultiStepScreenshot("Wikipedia", "https://en.wikipedia.org/wiki/Main_Page") { reader, _, input, screenshotEngine, steps ->
                steps.add(StepScreenshot("Step 1: Loaded Wikipedia", screenshotEngine.screenshot()))
                
                input.type("input[name='search']", "Android")
                steps.add(StepScreenshot("Step 2: Typed 'Android'", screenshotEngine.screenshot()))

                input.press("input[name='search']", "Enter")
                kotlinx.coroutines.delay(1800)
                steps.add(StepScreenshot("Step 3: Search Results", screenshotEngine.screenshot()))

                val title = reader.title()
                val header = reader.querySelector("h1#firstHeading, h1")?.text ?: "N/A"
                "Search Submitted for 'Android'\n\nPage Title: $title\nHeading: $header"
            }
        }

        btnLoginForm.setOnClickListener {
            runScrapeAndMultiStepScreenshot("Login Form", "https://the-internet.herokuapp.com/login") { reader, _, input, screenshotEngine, steps ->
                steps.add(StepScreenshot("Step 1: Initial Login Page", screenshotEngine.screenshot()))

                input.type("#username", "tomsmith")
                input.type("#password", "SuperSecretPassword!")
                steps.add(StepScreenshot("Step 2: Credentials Filled", screenshotEngine.screenshot()))

                input.click("button[type='submit']")
                kotlinx.coroutines.delay(1200)
                steps.add(StepScreenshot("Step 3: Login Submitted", screenshotEngine.screenshot()))

                val flash = reader.querySelector("#flash")?.text ?: "N/A"
                "Submitted Login Form (tomsmith / ********)\n\nFlash Result: $flash"
            }
        }

        btnPizzaForm.setOnClickListener {
            runScrapeAndMultiStepScreenshot("Pizza Form", "https://httpbin.org/forms/post") { reader, _, input, screenshotEngine, steps ->
                steps.add(StepScreenshot("Step 1: Loaded Pizza Form", screenshotEngine.screenshot()))

                input.type("input[name='custname']", "John Doe")
                input.type("input[name='custtel']", "+15550199")
                input.type("input[name='custemail']", "john@example.com")
                input.click("input[name='size'][value='medium']")
                input.click("input[name='topping'][value='bacon']")
                input.click("input[name='topping'][value='cheese']")
                input.fillTime("input[name='delivery']", "11:30")
                input.type("textarea[name='comments']", "Extra crispy crust please!")
                steps.add(StepScreenshot("Step 2: All Form Fields & Time Typed", screenshotEngine.screenshot()))

                input.click("button")
                kotlinx.coroutines.delay(1500)
                steps.add(StepScreenshot("Step 3: Server Confirmation", screenshotEngine.screenshot()))

                val responseText = reader.text().take(250)
                "Submitted Full Pizza Form (John Doe, Medium, Bacon+Cheese, Delivery 11:30)\n\nResponse:\n$responseText"
            }
        }
    }

    private fun runScrapeAndMultiStepScreenshot(
        name: String,
        url: String,
        action: suspend (PlatformReader, PlatformScriptEngine, PlatformInputEngine, PlatformScreenshotEngine, MutableList<StepScreenshot>) -> String
    ) {
        lifecycleScope.launch(Dispatchers.Main) {
            setLoading(true)
            layoutStepsContainer.removeAllViews()
            tvScrapeResults.text = "Executing $name pipeline on $url..."
            
            val config = BrowserConfig(enableProtocolBackend = false)
            val session = PageSession(this@ScrapeReadActivity, Viewport.Phone, config)
            val steps = mutableListOf<StepScreenshot>()

            try {
                session.initialize()
                val nav = PlatformNavigator(session, config)
                val script = PlatformScriptEngine(session, config)
                val reader = PlatformReader(session, script, config)
                val input = PlatformInputEngine(session, script, reader, config)
                val screenshotEngine = PlatformScreenshotEngine(session, config)

                nav.goto(url, WaitUntil.Load)
                val textOutput = action(reader, script, input, screenshotEngine, steps)
                
                renderStepScreenshots(steps)

                tvScrapeResults.text = "$textOutput\n\n[Captured ${steps.size} Step-by-Step Screenshots]"
            } catch (e: Exception) {
                tvScrapeResults.text = "$name Error: ${e.message}"
            } finally {
                session.close()
                setLoading(false)
            }
        }
    }

    private fun renderStepScreenshots(steps: List<StepScreenshot>) {
        layoutStepsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        for (step in steps) {
            val stepView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            val titleTv = TextView(this).apply {
                text = step.title
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (4 * density).toInt())
            }

            val imageView = ImageView(this).apply {
                val widthPx = (240 * density).toInt()
                val heightPx = (420 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(widthPx, heightPx)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFFE0E0E0.toInt())
            }

            val bitmap = BitmapFactory.decodeByteArray(step.bytes, 0, step.bytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }

            stepView.addView(titleTv)
            stepView.addView(imageView)
            layoutStepsContainer.addView(stepView)
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnHackerNews.isEnabled = !loading
        btnWikipedia.isEnabled = !loading
        btnLoginForm.isEnabled = !loading
        btnPizzaForm.isEnabled = !loading
    }
}
