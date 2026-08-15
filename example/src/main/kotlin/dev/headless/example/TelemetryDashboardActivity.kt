package dev.headless.example

import android.os.Bundle
import android.view.MenuItem
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
import dev.headless.browser.platform.PlatformScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

public class TelemetryDashboardActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var btnRefreshTelemetry: Button
    private lateinit var tvTelemetryDashboard: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemetry_dashboard)

        supportActionBar?.title = "Telemetry & Process Health"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        btnRefreshTelemetry = findViewById(R.id.btnRefreshTelemetry)
        tvTelemetryDashboard = findViewById(R.id.tvTelemetryDashboard)

        btnRefreshTelemetry.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                progressBar.visibility = android.view.View.VISIBLE
                tvTelemetryDashboard.text = "Running diagnostic session probe..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@TelemetryDashboardActivity, Viewport.Phone, config)
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
                        === TELEMETRY METRICS ===
                        
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
                    tvTelemetryDashboard.text = "Error: ${e.message}"
                } finally {
                    session.close()
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
