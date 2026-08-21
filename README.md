# WebDroid

A browser automation library for Android. Drive live Chromium pages programmatically inside Android applications without external servers, Node.js runtimes, USB cables, or visible UI.

**[API reference (KDoc)](https://loyality7.github.io/webdroid/)**

## Architecture

WebDroid hosts an in-process, offscreen `android.webkit.WebView` instance sharing the system Chromium engine.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Host Android Application                           │
│                                                                             │
│  ┌──────────────────────┐   Coroutine API      ┌─────────────────────────┐  │
│  │   Application Logic  │ ───────────────────> │         WebDroid          │  │
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
- **Dual Backend, Chosen per Capability**: A platform backend (`androidx.webkit`) serves every device. When `enableProtocolBackend = true` and the device's WebView answers over CDP, the SDK additionally opens that connection after the first navigation and prefers it for text, DOM, script evaluation and clicks — with an automatic, silent fall-back to the platform backend if the connection can't be made.
- **Automatic Renderer Recovery**: Uses `WebViewRenderProcessClient` to detect unresponsive renderers and trigger automatic recovery.
- **Whole-Task Timeout**: `BrowserConfig.timeouts.totalMillis` bounds an entire session's lifetime, not just each individual stage — a chain of calls that each stay under their own timeout can still be cut off once the total budget is spent.
- **Zero-Telemetry Local Metrics**: In-memory `SessionMetrics` diagnostic tracking (navigations, JS execution time, memory pressure events, crashes) with zero external data transmission.
- **SSRF & Security Guards**: Enforces `SsrfGuard` URL validation and strict opt-in gating for DevTools debugging.

## Security Notice

Enabling the protocol backend (`enableProtocolBackend = true`) calls `android.webkit.WebView.setWebContentsDebuggingEnabled(true)`.

On Android, web contents debugging is a process-wide setting that exposes all WebViews in the application to Chrome DevTools over USB (`chrome://inspect`). Keep `enableProtocolBackend = false` in production builds unless remote inspection is explicitly required.

With it `true`, the SDK also actively connects to that endpoint itself after the page's first navigation, to serve DOM/text/script/click calls over CDP where available. This is opportunistic: connection failure on a given device silently falls back to the platform backend, and `page.capabilities().protocolBackend` reports whether it actually connected for this session.

## Installation

To include WebDroid in your project:

```kotlin
repositories {
    mavenCentral()
    mavenLocal() // For local builds (`./gradlew publishToMavenLocal`)
}

dependencies {
    // Local build artifact (generated via `./gradlew publishToMavenLocal`):
    implementation("dev.webdroid:webdroid:1.1.0-SNAPSHOT")

    // Or include directly as a multi-module project dependency:
    // implementation(project(":headless"))
}
```

*Note: Artifacts are currently built from source or published to `mavenLocal()`. Maven Central release deployment will accompany the v1.1.0 release.*

## API Usage Examples

### Navigation and DOM Scraping via HeadlessBrowser Facade

```kotlin
import dev.webdroid.BrowserConfig
import dev.webdroid.HeadlessBrowser
import dev.webdroid.Viewport
import dev.webdroid.WaitUntil

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
import dev.webdroid.Page

suspend fun submitForm(page: Page) {
    page.type("#username", "alice")
    page.type("#password", "Password123!")
    page.fillTime("#appointment-time", "14:30")
    page.press("#password", "Enter")
}
```

### Diagnostic Screenshot

```kotlin
import dev.webdroid.Page

suspend fun captureScreenshot(page: Page): ByteArray {
    return page.screenshot()
}
```

### Capability Probing

```kotlin
val caps = page.capabilities()
println("Protocol backend connected: ${caps.protocolBackend}")
println("Screenshots supported: ${caps.screenshots}")
```

## System Constraints

- **Threading Model**: Main-thread WebView calls are marshaled internally. Long-running sessions should be bound to a `ForegroundService`.
- **Cookie Storage**: `CookieManager` is process-global. Call `page.clearCookies()` between distinct sessions to prevent cookie bleed.
- **Total Task Timeout**: `BrowserConfig.timeouts.totalMillis` (default 120s) bounds a session's whole lifetime from creation, not per call. A session held open across many independent operations past that budget starts raising `ErrorCode.TIMEOUT` — raise `totalMillis` for long-lived sessions.
- **Requirements**: Minimum SDK 26 (Android 8.0+).

## License

Licensed under the Apache License, Version 2.0.
