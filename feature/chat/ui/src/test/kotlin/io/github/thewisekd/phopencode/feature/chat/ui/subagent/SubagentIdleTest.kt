package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
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
 * The badge, header and list come to rest in a plain activity, the way the app's ActivityScenario
 * tests open the chat. A pulsing live badge or a spinner while loading would keep posting frames,
 * and Robolectric's `idle()` would never return. The looper is driven by hand as in the other
 * screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SubagentIdleTest {
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
    fun `a live badge comes to rest`() {
        assertComesToRest { SubagentBadge(LOADED, onIntent = {}) }
    }

    @Test
    fun `a badge of ended subagents comes to rest`() {
        assertComesToRest { SubagentBadge(LOADED.copy(subagents = TREE.map { it.ended() }), onIntent = {}) }
    }

    @Test
    fun `a loading header comes to rest`() {
        assertComesToRest { SubagentHeader(SubagentState(), onIntent = {}) }
    }

    @Test
    fun `a header with live subagents and an error comes to rest`() {
        assertComesToRest { SubagentHeader(CHILD.copy(error = ChatFailure.Unreachable), onIntent = {}) }
    }

    @Test
    fun `a loading list comes to rest`() {
        assertComesToRest { SubagentListScreen(LIST_ROUTE, SubagentState(), onIntent = {}) }
    }

    @Test
    fun `a list with live subagents comes to rest`() {
        assertComesToRest { SubagentListScreen(LIST_ROUTE, LOADED, onIntent = {}) }
    }

    @Test
    fun `an empty list comes to rest`() {
        assertComesToRest { SubagentListScreen(LIST_ROUTE, SubagentState(loading = false), onIntent = {}) }
    }

    @Test
    fun `a failed list comes to rest`() {
        assertComesToRest { SubagentListScreen(LIST_ROUTE, LOADED.copy(error = ChatFailure.Unreachable), onIntent = {}) }
    }
}
