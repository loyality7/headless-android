package dev.webdroid.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.WaitUntil
import dev.webdroid.core.PageSession
import dev.webdroid.fixtures.Fixture
import dev.webdroid.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformInputEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var reader: PlatformReader
    private lateinit var inputEngine: PlatformInputEngine

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true)
        session = PageSession(context, null, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
        scriptEngine = PlatformScriptEngine(session, config)
        reader = PlatformReader(session, scriptEngine, config)
        inputEngine = PlatformInputEngine(session, scriptEngine, reader, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun typeFiresInputAndChangeEvents() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val setupScript = """
            (function() {
                var inp = document.createElement('input');
                inp.id = 'test-input';
                window.__typed_events = [];
                inp.addEventListener('input', function(e) { window.__typed_events.push('input'); });
                inp.addEventListener('change', function(e) { window.__typed_events.push('change'); });
                document.body.appendChild(inp);
                return 'added';
            })();
        """.trimIndent()

        scriptEngine.evaluate(setupScript)
        inputEngine.type("#test-input", "hello")

        val valResult = scriptEngine.evaluate("document.getElementById('test-input').value")
        val eventsResult = scriptEngine.evaluate("JSON.stringify(window.__typed_events)")

        assertEquals("\"hello\"", valResult)
        assertTrue("Events should include input and change", eventsResult?.contains("input") == true && eventsResult.contains("change"))
    }

    @Test
    fun clickTriggersElementClickHandler() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val setupScript = """
            (function() {
                var btn = document.createElement('button');
                btn.id = 'test-btn';
                window.__clicked = false;
                btn.onclick = function() { window.__clicked = true; };
                document.body.appendChild(btn);
                return 'added';
            })();
        """.trimIndent()

        scriptEngine.evaluate(setupScript)
        inputEngine.click("#test-btn")

        val clickedResult = scriptEngine.evaluate("window.__clicked")
        assertEquals("true", clickedResult)
    }

    @Test
    fun hoverFiresMouseOverEvent() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val setupScript = """
            (function() {
                var div = document.createElement('div');
                div.id = 'test-hover';
                window.__hovered = false;
                div.onmouseover = function() { window.__hovered = true; };
                document.body.appendChild(div);
                return 'added';
            })();
        """.trimIndent()

        scriptEngine.evaluate(setupScript)
        inputEngine.hover("#test-hover")

        val hoveredResult = scriptEngine.evaluate("window.__hovered")
        assertEquals("true", hoveredResult)
    }

    @Test
    fun selectOptionUpdatesSelectValue() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val setupScript = """
            (function() {
                var sel = document.createElement('select');
                sel.id = 'test-select';
                var opt1 = document.createElement('option'); opt1.value = 'a'; opt1.text = 'A';
                var opt2 = document.createElement('option'); opt2.value = 'b'; opt2.text = 'B';
                sel.appendChild(opt1); sel.appendChild(opt2);
                document.body.appendChild(sel);
                return 'added';
            })();
        """.trimIndent()

        scriptEngine.evaluate(setupScript)
        inputEngine.selectOption("#test-select", "b")

        val valResult = scriptEngine.evaluate("document.getElementById('test-select').value")
        assertEquals("\"b\"", valResult)
    }

    @Test
    fun missingSelectorRaisesSelectorNotFound() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking { inputEngine.click("#missing-button", timeoutMillis = 100) }
        }
        assertEquals(ErrorCode.SELECTOR_NOT_FOUND, ex.code)
    }

    @Test
    fun liveInternetFormInputTest() = runBlocking {
        navigator.goto("https://example.com", WaitUntil.Load)

        val setupScript = """
            (function() {
                var inp = document.createElement('input');
                inp.id = 'live-search-input';
                document.body.appendChild(inp);
                return 'added';
            })();
        """.trimIndent()

        scriptEngine.evaluate(setupScript)
        inputEngine.type("#live-search-input", "headless testing")

        val valResult = scriptEngine.evaluate("document.getElementById('live-search-input').value")
        assertEquals("\"headless testing\"", valResult)
    }
}
