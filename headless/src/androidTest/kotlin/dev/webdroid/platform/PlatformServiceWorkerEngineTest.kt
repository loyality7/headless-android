package dev.webdroid.platform

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import dev.webdroid.BrowserConfig
import dev.webdroid.ResourceType
import dev.webdroid.core.PageSession
import dev.webdroid.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlatformServiceWorkerEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var router: PlatformRouter

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        session = PageSession(context, null, BrowserConfig(allowPrivateAddresses = true))
        session.initialize()
        router = PlatformRouter()
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun serviceWorkerSetupAttachesOrGracefullySkipsIfUnsupported() {
        val isSupported = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        val attached = router.enableServiceWorkerInterception()
        assertEquals(isSupported, attached)
    }

    @Test
    fun unconfiguredServiceWorkerRulesLeaveRequestsUnchanged() {
        val dummyRequest = TestResourceRequest(site.url("/sw-asset.js"))
        val response = router.interceptRequest(dummyRequest)
        assertNull("Unconfigured router should leave requests unchanged (return null)", response)
    }

    @Test
    fun serviceWorkerRequestsAreBlockedBySharedRouterRules() {
        router.blockTypes(ResourceType.Images)
        val interceptedUrls = CopyOnWriteArrayList<String>()
        router.onRequest { interceptedUrls.add(it) }

        val imageRequest = TestResourceRequest(site.url("/sw-fetch-image.png"))
        val response = router.interceptRequest(imageRequest)

        assertNotNull("Image request for ServiceWorker should be intercepted", response)
        assertTrue("Request hook should capture ServiceWorker request", interceptedUrls.contains(site.url("/sw-fetch-image.png")))
    }

    private class TestResourceRequest(private val urlString: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(urlString)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
