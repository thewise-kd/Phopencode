package io.github.thewisekd.phopencode.feature.settings.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.ui.licenses.LicensesScreen
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.PreferencesScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.fail

/** Far more main thread steps than drawing a still screen takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the screen is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * Every settings screen comes to rest in a plain activity, the way the app's ActivityScenario tests
 * open them, while loading and while deleting. An endless animation, such as an indeterminate
 * spinner, would keep posting frames, and Robolectric's `idle()` would never return. The looper is
 * driven by hand as in the other features' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SettingsIdleTest {
    private fun assertComesToRest(content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { PhopencodeTheme { content() } }
        val looper = shadowOf(Looper.getMainLooper())
        var steps = 0
        while (steps < STEP_LIMIT) {
            if (!looper.isIdle) {
                looper.runOneTask()
            } else {
                val wait = untilNextTask(looper) ?: return
                if (wait > REST_HORIZON) return
                ShadowSystemClock.advanceBy(wait)
            }
            steps++
        }
        fail("the main thread still had work due within $REST_HORIZON after $steps steps")
    }

    /** How long until the looper's next task, or null when nothing is scheduled. */
    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    @Test
    fun `loading and deleting preferences come to rest`() {
        assertComesToRest { PreferencesScreen(SettingsRoute.Preferences, PreferencesState(), onIntent = {}) }
        val deleting = PreferencesState(loading = false, deletion = DataDeletion.Deleting)
        assertComesToRest { PreferencesScreen(SettingsRoute.Preferences, deleting, onIntent = {}) }
    }

    @Test
    fun `the licenses come to rest`() {
        assertComesToRest { LicensesScreen(SettingsRoute.Licenses, onClose = {}) }
    }
}
