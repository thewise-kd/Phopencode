package io.github.thewisekd.phopencode.feature.chat.ui.composer

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
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
 * The composer comes to rest in a plain activity, the way the app's ActivityScenario tests open the
 * chat. An endless animation, such as core/ui's `Loading` spinner while sending or stopping, would
 * keep posting frames, and Robolectric's `idle()` would never return. The looper is driven by hand as
 * in the other screens' idle tests: a due task runs, a task due within [REST_HORIZON] is jumped to,
 * and the screen is at rest once nothing is due within the horizon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ComposerIdleTest {
    private fun assertComesToRest(state: ComposerState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { Composer(state = state, onIntent = {}) }
        }
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
    fun `a disabled composer comes to rest`() {
        assertComesToRest(ComposerState())
    }

    @Test
    fun `a ready composer comes to rest`() {
        assertComesToRest(ready(DRAFT))
    }

    @Test
    fun `sending comes to rest`() {
        assertComesToRest(ready(DRAFT).copy(sending = true))
    }

    @Test
    fun `running and stopping come to rest`() {
        assertComesToRest(running().copy(stopping = true))
    }

    @Test
    fun `an error comes to rest`() {
        assertComesToRest(running(DRAFT).copy(error = ComposerError(ComposerAction.Stop, ChatFailure.Conflict)))
    }
}
