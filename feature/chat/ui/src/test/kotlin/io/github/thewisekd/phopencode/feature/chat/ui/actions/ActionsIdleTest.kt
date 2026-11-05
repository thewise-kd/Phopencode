package io.github.thewisekd.phopencode.feature.chat.ui.actions

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
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

/** Far more main thread steps than drawing still actions takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the actions are at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * The menu button, the open menu, the confirmation, the status and the error, and a prompt's
 * actions, come to rest in a plain activity, the way the app's ActivityScenario tests open the chat.
 * An endless animation, such as a spinner while a compaction runs, would keep posting frames, and
 * Robolectric's `idle()` would never return. The looper is driven by hand as in the other screens'
 * idle tests.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ActionsIdleTest {
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

    private fun assertMenuComesToRest(state: ActionsState) {
        assertComesToRest { SessionActionsMenu(state, onIntent = {}) }
    }

    @Test
    fun `the menu button before the session is ready comes to rest`() {
        assertMenuComesToRest(ActionsState())
    }

    @Test
    fun `the menu button comes to rest`() {
        assertMenuComesToRest(READY)
    }

    @Test
    fun `the open menu comes to rest`() {
        assertMenuComesToRest(READY.copy(menuOpen = true))
    }

    @Test
    fun `the open menu while an action runs comes to rest`() {
        assertMenuComesToRest(READY.copy(menuOpen = true, running = SessionAction.Compact))
    }

    @Test
    fun `the open menu with a failure comes to rest`() {
        assertMenuComesToRest(READY.copy(menuOpen = true, error = ActionError(SessionAction.Compact, ChatFailure.Rejected)))
    }

    @Test
    fun `the compaction's confirmation comes to rest`() {
        assertMenuComesToRest(READY.copy(confirming = SessionAction.Compact))
    }

    @Test
    fun `a fork's confirmation comes to rest`() {
        assertMenuComesToRest(READY.copy(confirming = SessionAction.Fork(PROMPT)))
    }

    @Test
    fun `a compaction on its way comes to rest`() {
        assertMenuComesToRest(READY.copy(running = SessionAction.Compact))
    }

    @Test
    fun `a compaction on the server comes to rest`() {
        assertMenuComesToRest(READY.copy(compacting = true))
    }

    @Test
    fun `a fork on its way comes to rest`() {
        assertMenuComesToRest(READY.copy(running = SessionAction.Fork(null)))
    }

    @Test
    fun `a failed action comes to rest`() {
        assertMenuComesToRest(READY.copy(error = ActionError(SessionAction.Fork(PROMPT), ChatFailure.ServerFailed)))
    }

    @Test
    fun `a prompt's actions come to rest`() {
        assertComesToRest { MessageActions(PROMPT, READY, onIntent = {}, onDismiss = {}) }
    }

    @Test
    fun `a prompt's actions while an action runs come to rest`() {
        assertComesToRest { MessageActions(PROMPT, READY.copy(running = SessionAction.Compact), onIntent = {}, onDismiss = {}) }
    }

    @Test
    fun `a prompt's actions with the menu and a fork's confirmation open come to rest`() {
        val state = READY.copy(confirming = SessionAction.Fork(PROMPT))
        assertComesToRest {
            Column {
                SessionActionsMenu(state, onIntent = {})
                MessageActions(PROMPT, state, onIntent = {}, onDismiss = {})
            }
        }
    }
}
