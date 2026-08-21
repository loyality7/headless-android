package dev.webdroid.example

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.webdroid.BrowserConfig
import dev.webdroid.Viewport
import dev.webdroid.core.PageSession
import dev.webdroid.platform.PlatformScriptEngine
import dev.webdroid.platform.PlatformStorageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

public class StorageInspectorActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var btnFetchCookies: Button
    private lateinit var btnClearStorage: Button
    private lateinit var tvStorageResults: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_inspector)

        supportActionBar?.title = "Storage & Cookie Inspector"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        btnFetchCookies = findViewById(R.id.btnFetchCookies)
        btnClearStorage = findViewById(R.id.btnClearStorage)
        tvStorageResults = findViewById(R.id.tvStorageResults)

        btnFetchCookies.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                progressBar.visibility = android.view.View.VISIBLE
                tvStorageResults.text = "Injecting sample cookie and reading..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@StorageInspectorActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val script = PlatformScriptEngine(session, config)
                    val storage = PlatformStorageEngine(session, script, config)

                    val targetUrl = "https://news.ycombinator.com"
                    storage.setCookie(targetUrl, "headless_android_session=auth_token_8899; Path=/")
                    val cookies = storage.getCookies(targetUrl)

                    val cookieList = cookies.joinToString("\n") { "${it.name} = ${it.value}" }
                    tvStorageResults.text = "Domain Cookies ($targetUrl):\n\n$cookieList"
                } catch (e: Exception) {
                    tvStorageResults.text = "Error: ${e.message}"
                } finally {
                    session.close()
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }

        btnClearStorage.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                progressBar.visibility = android.view.View.VISIBLE
                tvStorageResults.text = "Clearing process cookies & WebStorage..."
                val config = BrowserConfig(enableProtocolBackend = false)
                val session = PageSession(this@StorageInspectorActivity, Viewport.Phone, config)
                try {
                    session.initialize()
                    val script = PlatformScriptEngine(session, config)
                    val storage = PlatformStorageEngine(session, script, config)

                    storage.clearCookies()
                    storage.clearStorage()
                    tvStorageResults.text = "Cookies & WebStorage cleared successfully!"
                } catch (e: Exception) {
                    tvStorageResults.text = "Error: ${e.message}"
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
