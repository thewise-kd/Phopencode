package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
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

/** Far more main thread steps than drawing a still switcher takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the switcher is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * The control and its open sheet come to rest in a plain activity, the way the app's
 * ActivityScenario tests open the chat. An endless animation, such as a spinner while the lists
 * load or a switch is on its way, would keep posting frames, and Robolectric's `idle()` would never
 * return. The sheet's own slide in is finite. The looper is driven by hand as in the other screens'
 * idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SwitcherIdleTest {
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
    fun `the control before the session is ready comes to rest`() {
        assertComesToRest { AgentModelSwitcher(SwitcherState(), onIntent = {}) }
    }

    @Test
    fun `the closed control comes to rest`() {
        assertComesToRest { AgentModelSwitcher(loaded(open = false), onIntent = {}) }
    }

    @Test
    fun `the open sheet comes to rest`() {
        assertComesToRest { AgentModelSwitcher(loaded(), onIntent = {}) }
    }

    @Test
    fun `the sheet while the lists load comes to rest`() {
        assertComesToRest { AgentModelSwitcher(SwitcherState(enabled = true, open = true, loading = true), onIntent = {}) }
    }

    @Test
    fun `the sheet while a switch is on its way comes to rest`() {
        assertComesToRest { AgentModelSwitcher(loaded().copy(switching = true), onIntent = {}) }
    }

    @Test
    fun `the sheet with a failed load comes to rest`() {
        assertComesToRest {
            AgentModelSwitcher(
                SwitcherState(enabled = true, open = true, error = SwitcherError(SwitcherAction.Load, ChatFailure.Unreachable)),
                onIntent = {},
            )
        }
    }

    @Test
    fun `the sheet with a failed switch comes to rest`() {
        assertComesToRest {
            AgentModelSwitcher(loaded().copy(error = SwitcherError(SwitcherAction.SwitchAgent, ChatFailure.Conflict)), onIntent = {})
        }
    }
}
