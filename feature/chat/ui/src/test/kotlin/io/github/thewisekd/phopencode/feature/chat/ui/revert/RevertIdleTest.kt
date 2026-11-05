package io.github.thewisekd.phopencode.feature.chat.ui.revert

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
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
 * The page comes to rest in a plain activity in each of its states, the way the app's
 * ActivityScenario tests open it. An endless animation, such as a spinner while loading or a step
 * on its way, would keep posting frames, and Robolectric's `idle()` would never return. The looper
 * is driven by hand as in the other screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RevertIdleTest {
    private fun assertComesToRest(state: RevertState) = assertComesToRest {
        TurnDiffScreen(DIFF_ROUTE, state, onIntent = {})
    }

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
    fun `a loading page comes to rest`() = assertComesToRest(RevertState())

    @Test
    fun `a loaded page comes to rest`() = assertComesToRest(LOADED)

    @Test
    fun `a page without file changes comes to rest`() = assertComesToRest(LOADED.copy(files = emptyList()))

    @Test
    fun `a failed load comes to rest`() =
        assertComesToRest(RevertState(loading = false, loadError = ChatFailure.Unreachable))

    @Test
    fun `a page staging its revert comes to rest`() = assertComesToRest(LOADED.copy(pending = RevertAction.Stage))

    @Test
    fun `a confirmation comes to rest`() = assertComesToRest(STAGED)

    @Test
    fun `a confirmation on its way comes to rest`() = assertComesToRest(STAGED.copy(pending = RevertAction.Commit))

    @Test
    fun `a revert staged for another turn comes to rest`() = assertComesToRest(STAGED_ELSEWHERE)

    @Test
    fun `a failed step comes to rest`() =
        assertComesToRest(STAGED.copy(error = RevertError(RevertAction.Commit, ChatFailure.Conflict)))
}
