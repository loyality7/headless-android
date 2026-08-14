# Scripts injected into page context are matched by name from Kotlin, so the
# members reached over the message bridge must survive shrinking.
-keepclassmembers class dev.headless.browser.** {
    @android.webkit.JavascriptInterface <methods>;
}
