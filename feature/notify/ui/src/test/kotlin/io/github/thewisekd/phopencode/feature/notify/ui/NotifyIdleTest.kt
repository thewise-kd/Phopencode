package io.github.thewisekd.phopencode.feature.notify.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import io.github.thewisekd.phopencode.feature.notify.ui.pending.PendingScreen
import io.github.thewisekd.phopencode.feature.notify.ui.setup.SetupScreen
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
 * Every notify screen comes to rest in a plain activity, the way the app's ActivityScenario tests
 * open them, while loading and while a step runs. An endless animation, such as an indeterminate
 * spinner, would keep posting frames, and Robolectric's `idle()` would never return. The looper is
 * driven by hand as in the other features' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class NotifyIdleTest {
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
    fun `a loading or working setup comes to rest`() {
        assertComesToRest { SetupScreen(NotifyRoute.Setup, SetupState(), onIntent = {}) }
        val installing = SetupState(loading = false, operation = SetupStep.Install)
        assertComesToRest { SetupScreen(NotifyRoute.Setup, installing, onIntent = {}) }
    }

    @Test
    fun `a loading or refreshing pending list comes to rest`() {
        assertComesToRest { PendingScreen(NotifyRoute.Pending, PendingState(), onIntent = {}) }
        val refreshing = PendingState(loading = false, refreshing = true)
        assertComesToRest { PendingScreen(NotifyRoute.Pending, refreshing, onIntent = {}) }
    }
}
