package ai.opencyvis.backend

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencyvis.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupActivityInstrumentedTest {

    @Test
    fun setupActivity_launches_successfully() {
        val scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.use {
            // Should have a title view
            onView(withId(R.id.setup_title)).check(matches(isDisplayed()))
            // Should have an action button
            onView(withId(R.id.setup_action_button)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun setupActivity_showsCorrectState_forDeviceWithWifi() {
        val scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.use {
            // On a device with WiFi, should NOT show "Connect to WiFi" screen
            onView(withId(R.id.setup_title)).check(matches(isDisplayed()))
            // The title should not be the WiFi error
            onView(withId(R.id.setup_title)).check(
                matches(
                    org.hamcrest.Matchers.not(
                        withText(R.string.setup_need_wifi_title)
                    )
                )
            )
        }
    }

    @Test
    fun setupActivity_progressBarHidden_initially() {
        val scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.use {
            // Progress bar should be GONE initially (not CONNECTING state)
            onView(withId(R.id.setup_progress)).check(
                matches(
                    withEffectiveVisibility(Visibility.GONE)
                )
            )
        }
    }

    @Test
    fun setupActivity_descriptionVisible() {
        val scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.use {
            // Description text view should be visible
            onView(withId(R.id.setup_description)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun setupActivity_inputFieldHidden_initially() {
        val scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.use {
            // Input field should be GONE initially (only shown in ADB_PAIR state)
            onView(withId(R.id.setup_input_layout)).check(
                matches(
                    withEffectiveVisibility(Visibility.GONE)
                )
            )
        }
    }
}
