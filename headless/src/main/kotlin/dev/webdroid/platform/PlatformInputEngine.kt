package dev.webdroid.platform

import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.core.PageSession
import dev.webdroid.core.SessionState
import org.json.JSONObject

/**
 * Handles synthetic user interactions (click, type, press, hover, scrollIntoView, selectOption)
 * on the platform backend.
 */
public class PlatformInputEngine(
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
    public suspend fun click(
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
    public suspend fun type(
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
                var specialTypes = ['time', 'date', 'datetime-local', 'month', 'week', 'color', 'number'];
                if (specialTypes.indexOf(el.type) !== -1) {
                    el.value = textToType;
                    el.setAttribute('value', textToType);
                } else {
                    el.value = '';
                    for (var i = 0; i < textToType.length; i++) {
                        var ch = textToType.charAt(i);
                        el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
                        el.value = el.value + ch;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new KeyboardEvent('keyup', { key: ch, bubbles: true }));
                    }
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
    public suspend fun press(
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
                var keyCode = (k === 'Enter') ? 13 : 0;
                el.dispatchEvent(new KeyboardEvent('keydown', { key: k, code: k, keyCode: keyCode, which: keyCode, bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keypress', { key: k, code: k, keyCode: keyCode, which: keyCode, bubbles: true }));
                if (k === 'Enter' && el.form) {
                    if (typeof el.form.requestSubmit === 'function') {
                        el.form.requestSubmit();
                    } else {
                        el.form.submit();
                    }
                }
                el.dispatchEvent(new KeyboardEvent('keyup', { key: k, code: k, keyCode: keyCode, which: keyCode, bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Hovers over element matching [selector].
     */
    public suspend fun hover(
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
    public suspend fun scrollIntoView(
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
    public suspend fun selectOption(
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
                var val = $escapedVal;
                el.value = val;
                if (el.options) {
                    for (var i = 0; i < el.options.length; i++) {
                        if (el.options[i].value === val || el.options[i].text === val) {
                            el.selectedIndex = i;
                            el.options[i].selected = true;
                            break;
                        }
                    }
                }
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new Event('input', { bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }

    /**
     * Sets the time value (HH:MM format) on a time input element matching [selector].
     */
    public suspend fun fillTime(
        selector: String,
        time: String,
        timeoutMillis: Long = 0,
    ): Unit = session.runInState(SessionState.Operating) {
        reader.waitForSelector(selector, timeoutMillis)

        val escapedSel = JSONObject.quote(selector)
        val escapedTime = JSONObject.quote(time)
        val script = """
            (function() {
                var el = document.querySelector($escapedSel);
                if (!el) return false;
                el.scrollIntoView({ block: 'center', inline: 'center' });
                el.focus();
                el.value = $escapedTime;
                el.setAttribute('value', $escapedTime);
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)
    }
}
