package dev.headless.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.widget.Button
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleAppDeviceTest {

    @Test
    fun testLiveMultiTabNavigationAndFeaturesOnDevice() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // 1. Test Tab 1 (Scrape & Read)
        scenario.onActivity { activity ->
            val btnHackerNews: Button = activity.findViewById(R.id.btnScrapeHackerNews)
            btnHackerNews.performClick()
        }

        var scrapeText = ""
        for (i in 0..20) {
            Thread.sleep(250)
            scenario.onActivity { activity ->
                val tvScrapeResults: TextView = activity.findViewById(R.id.tvScrapeResults)
                scrapeText = tvScrapeResults.text.toString()
            }
            if (scrapeText.contains("Title:")) break
        }
        assertTrue("Scrape Tab should yield page title", scrapeText.contains("Title:"))

        // 2. Test Tab 3 (Storage & Cookies)
        scenario.onActivity { activity ->
            val tabLayout: TabLayout = activity.findViewById(R.id.tabLayout)
            tabLayout.getTabAt(2)?.select()
            val btnFetchCookies: Button = activity.findViewById(R.id.btnFetchCookies)
            btnFetchCookies.performClick()
        }

        var storageText = ""
        for (i in 0..20) {
            Thread.sleep(250)
            scenario.onActivity { activity ->
                val tvStorageResults: TextView = activity.findViewById(R.id.tvStorageResults)
                storageText = tvStorageResults.text.toString()
            }
            if (storageText.contains("Domain Cookies")) break
        }
        assertTrue("Storage Tab should fetch domain cookies", storageText.contains("Domain Cookies"))

        // 3. Test Tab 4 (Telemetry & Diagnostics)
        scenario.onActivity { activity ->
            val tabLayout: TabLayout = activity.findViewById(R.id.tabLayout)
            tabLayout.getTabAt(3)?.select()
            val btnRefreshTelemetry: Button = activity.findViewById(R.id.btnRefreshTelemetry)
            btnRefreshTelemetry.performClick()
        }

        var telemetryText = ""
        for (i in 0..20) {
            Thread.sleep(250)
            scenario.onActivity { activity ->
                val tvTelemetryDashboard: TextView = activity.findViewById(R.id.tvTelemetryDashboard)
                telemetryText = tvTelemetryDashboard.text.toString()
            }
            if (telemetryText.contains("TELEMETRY SNAPSHOT")) break
        }
        assertTrue("Telemetry Tab should render diagnostic dashboard", telemetryText.contains("TELEMETRY SNAPSHOT"))

        scenario.close()
    }
}
