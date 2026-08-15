<div align="center">

# Headless Android SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/MinSDK-26%2B%20(Android%208.0%2B)-green.svg)](https://developer.android.com)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

**A production-grade, Playwright-shaped browser automation engine built natively for Android.**  
*Drive live Chromium pages programmatically inside ordinary Android apps — no external server, no Node.js runtime, no USB cable, and zero visible UI.*

</div>

---

## 💡 Overview

Desktop browser automation frameworks like Playwright, Puppeteer, and Selenium cannot run natively on Android devices. Hosted browser services introduce latency and privacy concerns, while Android's `chrome://inspect` requires a physical desktop connection over USB.

**Headless Android** solves this by turning system `android.webkit.WebView` — which shares the exact Chromium rendering core — into an in-process, headless automation host.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Your Android Application                           │
│                                                                             │
│  ┌──────────────────────┐   Suspendable API    ┌─────────────────────────┐  │
│  │   Host Controller    │ ───────────────────> │   Headless Android SDK  │  │
│  └──────────────────────┘                      └──────────┬──────────────┘  │
│                                                           │                 │
│                                                 Direct In-Process IPC       │
│                                                           │                 │
│                                                           ▼                 │
│                                                ┌─────────────────────────┐  │
│                                                │   android.webkit.WebView│  │
│                                                │     (Offscreen Host)    │  │
│                                                └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## ✨ Key Features

- **🚀 Offscreen Execution Engine**: Run full Chromium sessions cleanly offscreen (`AttachedToHost` or `Detached`) without window overlays or visual UI artifacts.
- **⚡ Playwright-Shaped Suspend API**: Intuitive Kotlin coroutine APIs for page navigation (`goto`), DOM extraction (`querySelector`, `text`, `content`), dynamic script evaluation (`evaluate`), and form automation (`fillTime`, `press`).
- **🛡️ Auto Renderer Recovery**: Monitors renderer health via `WebViewRenderProcessClient`. Automatically terminates and recovers hung or unresponsive renderers without crashing the host app.
- **📊 Zero-Telemetry Local Metrics**: In-memory diagnostics (`SessionMetrics`) track session duration, navigation counts, JS execution time, memory pressure events, and renderer crashes without transmitting external telemetry.
- **🔒 In-App Security & Guardrails**: Built-in SSRF protection (`SsrfGuard`) and strict opt-in gating for Chrome DevTools debugging capabilities.
- **📦 Ultra Lightweight**: Zero heavy native binaries. Keeps artifact size under ~1 MB by leveraging system Chromium dependencies.

---

## ⚠️ Security Notice & Debugging Opt-In

> [!WARNING]
> Enabling protocol backend debugging (`enableProtocolBackend = true`) invokes `android.webkit.WebView.setWebContentsDebuggingEnabled(true)`.
> 
> - **Process-Wide Impact**: On Android, enabling web contents debugging makes **all WebViews in the host application inspectable over USB** via `chrome://inspect`.
> - **Production Safety**: Always keep `enableProtocolBackend = false` in production builds unless remote DevTools inspection is explicitly required by your application.

---

## 🛠️ Installation

Add the SDK dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.headless:headless:1.0.0")
}
```

---

## 📖 Usage Examples

### 1. Navigation & Content Extraction

```kotlin
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader

suspend fun scrapeTopStory(context: Context) {
    val config = BrowserConfig(enableProtocolBackend = false)
    val session = PageSession(context, Viewport.Phone, config)
    session.initialize()

    try {
        val navigator = PlatformNavigator(session, config)
        val reader = PlatformReader(session, null, config)

        // Navigate and wait for DOM load completion
        navigator.goto("https://news.ycombinator.com", WaitUntil.Load)

        val title = reader.title()
        val topStory = reader.querySelector(".titleline > a")

        println("Page Title: $title")
        println("Top Headline: ${topStory?.text}")
        println("Headline URL: ${topStory?.attributes?.get("href")}")
    } finally {
        session.close()
    }
}
```

### 2. Form Automation & Native Keyboard Input

```kotlin
import dev.headless.browser.platform.PlatformInputEngine

suspend fun submitForm(session: PageSession, config: BrowserConfig) {
    val inputEngine = PlatformInputEngine(session, null, null, config)

    // Type text character-by-character into form inputs
    inputEngine.type("#username", "alice_dev")
    inputEngine.type("#password", "SecureSecret123!")

    // Fill HTML5 time input controls
    inputEngine.fillTime("#appointment-time", "14:30")

    // Submit form natively via Enter key
    inputEngine.press("#password", "Enter")
}
```

### 3. Visual Screenshot Capture

```kotlin
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.ScreenshotOptions

suspend fun captureDiagnosticScreenshot(session: PageSession, config: BrowserConfig): ByteArray {
    val screenshotEngine = PlatformScreenshotEngine(session, config)
    
    // Capture PNG screenshot of current offscreen DOM state
    return screenshotEngine.screenshot(ScreenshotOptions())
}
```

### 4. Zero-Telemetry Local Metrics

```kotlin
val metrics = session.metrics()
println("Session Duration: ${metrics.sessionDurationMs} ms")
println("Total Navigations: ${metrics.totalNavigations}")
println("JS Execution Count: ${metrics.totalJsEvaluations}")
println("Renderer Crashes: ${metrics.rendererCrashes}")
```

---

## 🏛️ Architecture & System Limits

| Aspect | Detail |
| :--- | :--- |
| **Threading Model** | Main-thread WebView operations are marshaled internally. Long-running sessions should be bound to a host `ForegroundService`. |
| **Cookie Storage** | Standard Android `CookieManager` is process-global. To isolate user sessions, call `storageEngine.clearAllData()` between tasks. |
| **Memory Management** | Automatic teardown hooks release offscreen views and unregister listeners upon `session.close()`. |
| **Device Compatibility** | Compatible with Android 8.0+ (API 26+) and standard Play Store WebView updates. |

---

## 📱 Interactive Example App (`:example`)

The repository includes a multi-screen demo application demonstrating real-world automation capabilities:

- **Scrape & Read**: Live search automation and step-by-step diagnostic screenshot gallery.
- **Screenshot Studio**: High-resolution DOM rendering and image capture.
- **Storage Inspector**: Process cookie extraction, injection, and storage clearing.
- **Telemetry Dashboard**: Real-time visualization of local session diagnostic metrics.

```bash
# Build and run example application on connected hardware
./gradlew :example:installDebug
```

---

## 🤝 Contributing

Contributions are welcome! Please ensure all code changes pass the automated test suite before submitting a Pull Request:

```bash
# Run unit tests and static lint analysis
./gradlew check

# Run on-device instrumentation tests on connected hardware
./gradlew :headless:connectedDebugAndroidTest
```

---

## 📄 License

```text
Copyright 2026 Headless Android Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
