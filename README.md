# Headless Android

A lightweight, Playwright-shaped browser automation SDK for Android. Drive live Chromium pages programmatically inside your Android application — with no external server, no USB cable, and no visible UI.

---

## ⚠️ Security Notice & Debugging Opt-In

> [!WARNING]
> Enabling the protocol backend (`enableProtocolBackend = true`) invokes `android.webkit.WebView.setWebContentsDebuggingEnabled(true)`.
> This is a **process-wide setting** on Android that enables Chrome DevTools inspectability over USB (`chrome://inspect`).
> 
> - **In Production**: Keep `enableProtocolBackend = false` unless protocol capabilities are required.
> - **Never enable debugging without explicit host-app opt-in.**

---

## Features & Capabilities

- **Invisible Offscreen Engine**: Hosts WebViews offscreen (`AttachedToHost` or `Detached`) without visible artifacts or window overlays.
- **Playwright-like API**: Simple suspendable APIs for navigation (`goto`), script evaluation (`evaluate`), DOM queries (`querySelector`), screenshot capture (`captureScreenshot`), and form automation (`fillTime`, `press`).
- **Resilient Lifecycle**: Automatic crash/OOM recovery (`handleRendererDeath`, `recover`), hang detection (`WebViewRenderProcessClient`), and zero-telemetry local metrics (`metrics()`).
- **Zero Heavy Dependencies**: Powered by standard Android WebView, Kotlin coroutines, `androidx.webkit`, and `kotlinx.serialization`.

---

## Quickstart & Integration Guide

### 1. Dependency Setup
Include the `:headless` artifact or module in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.headless:headless:1.0.0")
}
```

### 2. Basic Navigation & Scraping Example

```kotlin
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader

suspend fun scrapeExample(context: Context) {
    val config = BrowserConfig(enableProtocolBackend = false)
    val session = PageSession(context, Viewport.Phone, config)
    session.initialize()

    try {
        val navigator = PlatformNavigator(session, config)
        val reader = PlatformReader(session, null, config)

        navigator.goto("https://news.ycombinator.com", WaitUntil.Load)
        val title = reader.title()
        val topStory = reader.querySelector(".titleline > a")
        
        println("Title: $title")
        println("Top Story: ${topStory?.text}")
    } finally {
        session.close()
    }
}
```

---

## Process Constraints & System Limits

- **Process-Global Cookies**: Cookie storage is managed at the Android `CookieManager` process level. To isolate sessions, clear cookies explicitly between distinct tasks using `storageEngine.clearAllData()`.
- **Threading Model**: Main-thread operations are safely marshaled internally. Long-running automation tasks should run inside a host-app `ForegroundService`.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
