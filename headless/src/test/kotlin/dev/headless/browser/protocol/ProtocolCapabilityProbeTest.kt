package dev.headless.browser.protocol

import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtocolCapabilityProbeTest {

    @Before
    fun setUp() {
        ProtocolCapabilityProbe.clearCache()
    }

    @Test
    fun probeReturnsCapabilitiesWithoutCrashingOnJvm() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)

        val probe = ProtocolCapabilityProbe(null, config)

        val nullViewportCaps = probe.probeCapabilities(null)
        assertFalse("Null viewport should report screenshots false", nullViewportCaps.screenshots)
        assertFalse("Disabled protocol backend should report protocolBackend false", nullViewportCaps.protocolBackend)

        val phoneViewportCaps = probe.probeCapabilities(Viewport.Phone)
        assertTrue("Phone viewport should report screenshots true", phoneViewportCaps.screenshots)
    }

    @Test
    fun probeResultIsCachedAcrossCalls() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)

        val probe = ProtocolCapabilityProbe(null, config)

        val start = System.currentTimeMillis()
        val caps1 = probe.probeCapabilities(Viewport.Phone)
        val firstDuration = System.currentTimeMillis() - start

        val start2 = System.currentTimeMillis()
        val caps2 = probe.probeCapabilities(Viewport.Phone)
        val cachedDuration = System.currentTimeMillis() - start2

        assertEquals(caps1.documentStartScript, caps2.documentStartScript)
        assertEquals(caps1.webMessageChannel, caps2.webMessageChannel)
        assertTrue("Cached probe execution should be bounded and fast (<= cachedDuration)", cachedDuration <= firstDuration + 50)
    }
}
