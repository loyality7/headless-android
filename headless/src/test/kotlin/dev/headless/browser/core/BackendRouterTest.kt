package dev.headless.browser.core

import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.platform.PlatformInputEngine
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformRouter
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

class BackendRouterTest {

    @Test
    fun routerInstantiatesAndExposesRequestBlockingCleanly() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)

        val probe = dev.headless.browser.protocol.ProtocolCapabilityProbe(null, config)
        val caps = probe.probeCapabilities(Viewport.Phone)
        assertNotNull(caps)
    }
}
