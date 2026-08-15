package dev.headless.browser.platform

import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import org.json.JSONObject

/**
 * Handles synthetic user interactions (click, type, press, hover, scrollIntoView, selectOption)
 * on the platform backend.
 */
internal class PlatformInputEngine(
    private val session: PageSession,
    private val scriptEngine: PlatformScriptEngine,
    private val reader: PlatformReader,
    private val config: BrowserConfig,
) {

    /**
     * Clicks the element matching [selector] after scrolling it into view and waiting for it to exist.
     *
     * @throws BrowserException [ErrorCode.SELECTOR_NOT_FOUND] if element does not appear before timeout.
     */
    suspend fun click(
        selector: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escaped = JSONObject.quote(selector)
        val script = """
            (function() {
                var el = document.querySelector($escaped);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                el.focus();
                var pDown = new PointerEvent('pointerdown', { bubbles: true, cancelable: true });
                var mDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                var pUp = new PointerEvent('pointerup', { bubbles: true, cancelable: true });
                var mUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                var clickEvt = new MouseEvent('click', { bubbles: true, cancelable: true });
                el.dispatchEvent(pDown);
                el.dispatchEvent(mDown);
                el.dispatchEvent(pUp);
                el.dispatchEvent(mUp);
                el.click();
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Types [text] into the element matching [selector], firing input and change events for framework binding.
     *
     * @throws BrowserException [ErrorCode.SELECTOR_NOT_FOUND] if element does not appear before timeout.
     */
    suspend fun type(
        selector: String,
        text: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val escapedText = JSONObject.quote(text)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                el.focus();
                var textToType = $escapedText;
                for (var i = 0; i < textToType.length; i++) {
                    var ch = textToType.charAt(i);
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
                    el.value = (el.value || '') + ch;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: ch, bubbles: true }));
                }
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Presses [key] on element matching [selector].
     */
    suspend fun press(
        selector: String,
        key: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val escapedKey = JSONObject.quote(key)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.focus();
                var k = $escapedKey;
                el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keypress', { key: k, bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keyup', { key: k, bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Hovers over element matching [selector].
     */
    suspend fun hover(
        selector: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
                el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true, cancelable: false }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Scrolls element matching [selector] into view.
     */
    suspend fun scrollIntoView(
        selector: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Selects [value] on element matching [selector].
     */
    suspend fun selectOption(
        selector: String,
        value: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val escapedVal = JSONObject.quote(value)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                el.value = $escapedVal;
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new Event('input', { bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }
}
