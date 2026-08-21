package dev.headless.browser.security

import dev.headless.browser.platform.PlatformScriptEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceCappingTest {

    @Test
    fun truncatesOversizedStringRepresentation() {
        val longString = "A".repeat(1_500_000)
        val truncated = PlatformScriptEngine.truncateIfNeeded(longString)

        org.junit.Assert.assertNotNull(truncated)
        assertTrue(truncated!!.contains("[truncated 500000 characters]"))
        assertTrue(truncated.length < 1_001_000)
    }
}
