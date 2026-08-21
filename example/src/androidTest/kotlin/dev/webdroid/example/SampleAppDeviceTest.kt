package dev.webdroid.example

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class SampleAppDeviceTest {

    @Test
    public fun launchMainActivity_displaysAllFeatureButtons() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnOpenScrapeRead)).check(matches(isDisplayed()))
            onView(withId(R.id.btnOpenScreenshotStudio)).check(matches(isDisplayed()))
            onView(withId(R.id.btnOpenStorageInspector)).check(matches(isDisplayed()))
            onView(withId(R.id.btnOpenTelemetry)).check(matches(isDisplayed()))
        }
    }

    @Test
    public fun clickScrapeReadButton_opensScrapeReadActivity() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnOpenScrapeRead)).perform(click())
            onView(withId(R.id.btnHackerNews)).check(matches(isDisplayed()))
            onView(withId(R.id.btnPizzaForm)).check(matches(isDisplayed()))
        }
    }

    @Test
    public fun clickScreenshotStudioButton_opensScreenshotStudioActivity() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnOpenScreenshotStudio)).perform(click())
            onView(withId(R.id.btnCaptureHackerNews)).check(matches(isDisplayed()))
        }
    }
}
