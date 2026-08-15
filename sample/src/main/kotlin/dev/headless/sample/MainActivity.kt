package dev.headless.sample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnHackerNews: Button
    private lateinit var btnWikipedia: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvMetrics: TextView
    private lateinit var tvResults: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnHackerNews = findViewById(R.id.btnScrapeHackerNews)
        btnWikipedia = findViewById(R.id.btnScrapeWikipedia)
        progressBar = findViewById(R.id.progressBar)
        tvMetrics = findViewById(R.id.tvMetrics)
        tvResults = findViewById(R.id.tvResults)

        btnHackerNews.setOnClickListener {
            runScrapeJob("https://news.ycombinator.com") { session, reader ->
                val title = reader.title()
                val topStory = reader.querySelector(".titleline > a")?.text ?: "N/A"
                "Title: $title\n\nTop Story: $topStory"
            }
        }

        btnWikipedia.setOnClickListener {
            runScrapeJob("https://en.wikipedia.org/wiki/Main_Page") { session, reader ->
                val title = reader.title()
                val welcome = reader.querySelector("#mp-welcome")?.text ?: "N/A"
                "Title: $title\n\nWelcome Heading: $welcome"
            }
        }
    }

    private fun runScrapeJob(
        url: String,
        extractor: suspend (PageSession, PlatformReader) -> String,
    ) {
        lifecycleScope.launch {
            setLoading(true)
            tvResults.text = "Initializing headless browser session..."

            val config = BrowserConfig(enableProtocolBackend = false)
            val session = PageSession(this@MainActivity, Viewport.Phone, config)

            try {
                session.initialize()
                val navigator = PlatformNavigator(session, config)
                val scriptEngine = PlatformScriptEngine(session, config)
                val reader = PlatformReader(session, scriptEngine, config)

                tvResults.text = "Navigating offscreen to $url..."
                navigator.goto(url, WaitUntil.Load)

                val resultText = extractor(session, reader)
                val metrics = session.metrics()

                tvMetrics.text = "Metrics: duration=${metrics.sessionDurationMs}ms | navs=${metrics.totalNavigations} | jsEvals=${metrics.totalJsEvaluations}"
                tvResults.text = resultText
            } catch (ex: Exception) {
                tvResults.text = "Error: ${ex.message}"
            } finally {
                session.close()
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnHackerNews.isEnabled = !loading
        btnWikipedia.isEnabled = !loading
    }
}
