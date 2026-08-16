# Headless Android SDK

A browser automation library for Android. Drive live Chromium pages programmatically inside Android applications without external servers, Node.js runtimes, USB cables, or visible UI.

## Architecture

Headless Android hosts an in-process, offscreen `android.webkit.WebView` instance sharing the system Chromium engine.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Host Android Application                           │
│                                                                             │
│  ┌──────────────────────┐   Coroutine API      ┌─────────────────────────┐  │
│  │   Application Logic  │ ───────────────────> │   Headless Android SDK  │  │
│  └──────────────────────┘                      └──────────┬──────────────┘  │
│                                                           │                 │
│                                                 In-Process Interaction      │
│                                                           │                 │
│                                                           ▼                 │
│                                                ┌─────────────────────────┐  │
│                                                │   android.webkit.WebView│  │
│                                                │     (Offscreen Host)    │  │
│                                                └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Features

- **Offscreen Execution**: Runs WebViews offscreen (`AttachedToHost` or `Detached`) with zero visible UI artifacts.
- **Playwright-like API**: Coroutine-based APIs for navigation (`goto`), DOM reading (`querySelector`, `text`, `content`), script evaluation (`evaluate`), and form input (`fillTime`, `press`).
- **Automatic Renderer Recovery**: Uses `WebViewRenderProcessClient` to detect unresponsive renderers and trigger automatic recovery.
- **Zero-Telemetry Local Metrics**: In-memory `SessionMetrics` diagnostic tracking (navigations, JS execution time, memory pressure events, crashes) with zero external data transmission.
- **SSRF & Security Guards**: Enforces `SsrfGuard` URL validation and strict opt-in gating for DevTools debugging.

## Security Notice

Enabling the protocol backend (`enableProtocolBackend = true`) calls `android.webkit.WebView.setWebContentsDebuggingEnabled(true)`.

On Android, web contents debugging is a process-wide setting that exposes all WebViews in the application to Chrome DevTools over USB (`chrome://inspect`). Keep `enableProtocolBackend = false` in production builds unless remote inspection is explicitly required.

## Installation

To include Headless Android SDK in your project:

```kotlin
repositories {
    mavenCentral()
    mavenLocal() // For local builds (`./gradlew publishToMavenLocal`)
}

dependencies {
    // Local build artifact (generated via `./gradlew publishToMavenLocal`):
    implementation("dev.headless:headless-android:1.1.0-SNAPSHOT")

    // Or include directly as a multi-module project dependency:
    // implementation(project(":headless"))
}
```

*Note: Artifacts are currently built from source or published to `mavenLocal()`. Maven Central release deployment will accompany the v1.1.0 release.*

## API Usage Examples

### Navigation and DOM Scraping via HeadlessBrowser Facade

```kotlin
import dev.headless.browser.BrowserConfig
import dev.headless.browser.HeadlessBrowser
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil

suspend fun scrapeTopStory(context: Context) {
    val config = BrowserConfig(enableProtocolBackend = false)
    val browser = HeadlessBrowser.create(context, config)
    val page = browser.newPage(Viewport.Phone)

    try {
        page.goto("https://news.ycombinator.com", WaitUntil.Load)

        val title = page.title()
        val topStory = page.querySelector(".titleline > a")

        println("Page Title: $title")
        println("Top Headline: ${topStory?.text}")
    } finally {
        page.close()
        browser.close()
    }
}
```

### Form Input Automation

```kotlin
import dev.headless.browser.Page

suspend fun submitForm(page: Page) {
    page.type("#username", "alice")
    page.type("#password", "Password123!")
    page.fillTime("#appointment-time", "14:30")
    page.press("#password", "Enter")
}
```

### Diagnostic Screenshot

```kotlin
import dev.headless.browser.Page

suspend fun captureScreenshot(page: Page): ByteArray {
    return page.screenshot()
}
```

### Session Diagnostics

```kotlin
val metrics = session.metrics()
println("Duration: ${metrics.sessionDurationMs} ms")
println("Navigations: ${metrics.totalNavigations}")
println("JS Evaluations: ${metrics.totalJsEvaluations}")
```

## System Constraints

- **Threading Model**: Main-thread WebView calls are marshaled internally. Long-running sessions should be bound to a `ForegroundService`.
- **Cookie Storage**: `CookieManager` is process-global. Call `storageEngine.clearAllData()` between distinct sessions to prevent cookie bleed.
- **Requirements**: Minimum SDK 26 (Android 8.0+).

## License

Licensed under the Apache License, Version 2.0.
