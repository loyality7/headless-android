package dev.headless.browser.security

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SsrfGuardTest {

    @Before
    fun setUp() {
        SsrfGuard.allowLoopbackInTests = false
    }

    @After
    fun tearDown() {
        SsrfGuard.allowLoopbackInTests = true
    }

    @Test
    fun blocksLoopbackAndPrivateIpsByDefault() {
        val forbidden = listOf(
            "http://127.0.0.1/admin",
            "http://localhost/config",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/secret",
            "http://192.168.1.1/router",
            "http://172.16.0.1/internal"
        )

        for (url in forbidden) {
            val ex = assertThrows(BrowserException::class.java) {
                SsrfGuard.validateUri(url, allowPrivateAddresses = false)
            }
            assertEquals(ErrorCode.SSRF_BLOCKED, ex.code)
        }
    }

    @Test
    fun permitsPrivateIpsWhenConfigured() {
        val privateUrls = listOf(
            "http://127.0.0.1/admin",
            "http://10.0.0.1/secret"
        )

        for (url in privateUrls) {
            SsrfGuard.validateUri(url, allowPrivateAddresses = true)
        }
    }
}
