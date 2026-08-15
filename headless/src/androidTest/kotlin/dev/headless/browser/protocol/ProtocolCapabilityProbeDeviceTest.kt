package dev.headless.browser.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.core.PageSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolCapabilityProbeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ProtocolCapabilityProbe.clearCache()
    }

    @Test
    fun probeDeviceCapabilitiesLiveOnHardware() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        try {
            val caps = session.capabilities()
            assertNotNull("Capabilities object must not be null", caps)

            // Screenshots supported for phone viewport
            assertTrue("Screenshots must be true for phone viewport", caps.screenshots)

            // Probe results reported directly
            val stringRep = caps.toString()
            assertTrue("Capabilities toString should contain capabilities data", stringRep.contains("Capabilities("))
        } finally {
            session.close()
        }
    }
}
