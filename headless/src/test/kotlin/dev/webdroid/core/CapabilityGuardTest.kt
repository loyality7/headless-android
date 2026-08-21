package dev.webdroid.core

import dev.webdroid.BrowserException
import dev.webdroid.Capabilities
import dev.webdroid.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CapabilityGuardTest {

    @Test
    fun absentCapabilityRaisesUnsupportedImmediately() {
        val absentCaps = Capabilities(
            protocolBackend = false,
            documentStartScript = false,
            webMessageChannel = false,
            serviceWorkerInterception = false,
            rendererResponsiveness = false,
            screenshots = false,
        )

        try {
            CapabilityGuard.requireDocumentStartScript(absentCaps)
            fail("Should throw UNSUPPORTED for absent documentStartScript")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        }

        try {
            CapabilityGuard.requireScreenshots(absentCaps)
            fail("Should throw UNSUPPORTED for absent screenshots")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        }
    }

    @Test
    fun presentCapabilityPassesGuardCheck() {
        val presentCaps = Capabilities(
            protocolBackend = true,
            documentStartScript = true,
            webMessageChannel = true,
            serviceWorkerInterception = true,
            rendererResponsiveness = true,
            screenshots = true,
        )

        // Should execute without exception
        CapabilityGuard.requireDocumentStartScript(presentCaps)
        CapabilityGuard.requireWebMessageChannel(presentCaps)
        CapabilityGuard.requireServiceWorkerInterception(presentCaps)
        CapabilityGuard.requireRendererResponsiveness(presentCaps)
        CapabilityGuard.requireScreenshots(presentCaps)
    }
}
