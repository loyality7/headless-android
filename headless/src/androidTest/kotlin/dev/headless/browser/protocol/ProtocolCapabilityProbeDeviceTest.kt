package dev.headless.browser.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.core.PageSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The probe has to agree with reality, because every routing decision trusts it.
 *
 * Previously it tried only the bare `webview_devtools_remote` name, which does
 * not connect on Android 14, and so reported the protocol backend as absent on a
 * device where the CDP tests were passing against that very endpoint.
 */
@RunWith(AndroidJUnit4::class)
class ProtocolCapabilityProbeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ProtocolCapabilityProbe.clearCache()
    }

    @Test
    fun theProbeAgreesWithTargetDiscovery() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        try {
            val reportedReachable = session.capabilities().protocolBackend

            val actuallyDiscoverable = runCatching {
                ProtocolTargetDiscovery(session, config).discoverTargets().isNotEmpty()
            }.getOrDefault(false)

            assertEquals(
                "capabilities() said protocolBackend=$reportedReachable but discovery " +
                    "said $actuallyDiscoverable. A capability reported present must never fail in use, " +
                    "and one reported absent must not be quietly available.",
                actuallyDiscoverable,
                reportedReachable,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun theEndpointIsReachableWhenDebuggingIsEnabled() = runBlocking {
        val session = PageSession(context, Viewport.Phone, BrowserConfig(enableProtocolBackend = true))
        session.initialize()

        try {
            assertTrue(
                "the control endpoint must answer on a device where debugging was enabled",
                ProtocolTargetDiscovery.isEndpointReachable(),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun thePidSuffixedNameIsTriedFirst() {
        val candidates = ProtocolTargetDiscovery.socketCandidates()
        val pid = android.os.Process.myPid()

        assertEquals(
            "the pid-suffixed name is the one that connects on modern Android, so it must be first",
            "webview_devtools_remote_$pid",
            candidates.first(),
        )
        assertTrue(candidates.contains("webview_devtools_remote"))
    }

    @Test
    fun theProbeReportsAbsentWhenTheBackendIsNotEnabled() = runBlocking {
        val session = PageSession(context, Viewport.Phone, BrowserConfig(enableProtocolBackend = false))
        session.initialize()

        try {
            // Debugging is never switched on without the opt-in, so the backend
            // must be reported absent regardless of what the socket would answer.
            assertFalse(session.capabilities().protocolBackend)
        } finally {
            session.close()
        }
    }
}
