package dev.headless.example

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

public class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOpenScrapeRead).setOnClickListener {
            startActivity(Intent(this, ScrapeReadActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenScreenshotStudio).setOnClickListener {
            startActivity(Intent(this, ScreenshotStudioActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenStorageInspector).setOnClickListener {
            startActivity(Intent(this, StorageInspectorActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenTelemetry).setOnClickListener {
            startActivity(Intent(this, TelemetryDashboardActivity::class.java))
        }
    }
}
