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

### Local / Multi-Module Project

Include the `:headless` module in your project:

```kotlin
dependencies {
    implementation(project(":headless"))
}
```

### Maven Local / Published Artifact

To use from `mavenLocal()` after running `./gradlew :headless:publishToMavenLocal`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.headless:headless:1.0.0")
}
```

## API Usage Examples

### Navigation and DOM Scraping

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

        navigator.goto("https://news.ycombinator.com", WaitUntil.Load)

        val title = reader.title()
        val topStory = reader.querySelector(".titleline > a")

        println("Page Title: $title")
        println("Top Headline: ${topStory?.text}")
    } finally {
        session.close()
    }
}
```

### Form Input Automation

```kotlin
import dev.headless.browser.platform.PlatformInputEngine

suspend fun submitForm(session: PageSession, config: BrowserConfig) {
    val inputEngine = PlatformInputEngine(session, null, null, config)

    inputEngine.type("#username", "alice")
    inputEngine.type("#password", "Password123!")
    inputEngine.fillTime("#appointment-time", "14:30")
    inputEngine.press("#password", "Enter")
}
```

### Diagnostic Screenshot

```kotlin
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.ScreenshotOptions

suspend fun captureScreenshot(session: PageSession, config: BrowserConfig): ByteArray {
    val screenshotEngine = PlatformScreenshotEngine(session, config)
    return screenshotEngine.screenshot(ScreenshotOptions())
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
