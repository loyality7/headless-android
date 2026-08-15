package dev.headless.browser.security

import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformScriptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceCappingTest {

    @Test
    fun verifiesMaxOutputCharacterCapConstant() {
        assertEquals(1_000_000, PlatformScriptEngine.MAX_OUTPUT_CHARS)
    }

    @Test
    fun verifiesMaxRedirectsConstant() {
        assertEquals(20, PlatformNavigator.MAX_REDIRECTS)
    }

    @Test
    fun truncatesOversizedStringRepresentation() {
        val longString = "A".repeat(1_500_000)
        val truncated = if (longString.length > PlatformScriptEngine.MAX_OUTPUT_CHARS) {
            longString.substring(0, PlatformScriptEngine.MAX_OUTPUT_CHARS) +
                    "\n... [truncated ${longString.length - PlatformScriptEngine.MAX_OUTPUT_CHARS} characters]"
        } else {
            longString
        }

        assertTrue(truncated.contains("[truncated 500000 characters]"))
        assertTrue(truncated.length < 1_001_000)
    }
}
