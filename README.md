# headless-android

A browser automation library for Android. Playwright-shaped control of a live
Chromium page, running inside an ordinary app — no server, no USB cable, no
visible UI.

Nothing on Android can drive a web page programmatically. Playwright, Puppeteer
and Selenium are desktop-only; hosted browsers are a network round trip away;
Appium drives the app, not the page; `chrome://inspect` needs a desktop over USB.

Android WebView *is* Chromium — same renderer, same JavaScript engine, updated
through the Play Store, on every device, costing zero bytes of app size. It
exposes a control interface on a local abstract socket. This is the client for
it.

## Status

Pre-alpha. The feasibility spike is written and unrun. Nothing is published.

## Running the spike

Needs a connected device — an emulator cannot answer the OEM and SELinux
questions the spike exists to settle.

```
./gradlew :spike:connectedAndroidTest
adb logcat -s spike:I
```

Every measurement is logged as `MEASUREMENT <key> = <value>`.

## Stack

Kotlin, coroutines. `android.net.LocalSocket` for transport with a hand-written
RFC 6455 client over its streams, `kotlinx.serialization` for the protocol,
`androidx.webkit` for the platform backend. Three runtime dependencies, and the
artifact stays around 1 MB.

## Security

Enabling web contents debugging is process-wide and makes every WebView in the
app inspectable over USB while it is on. Android documents this as a production
security liability. It is an explicit opt-in here, never a default.

Solving challenges, defeating bot detection, rotating proxies and spoofing
fingerprints are out of scope permanently. This is an automation library.
