package io.github.thewisekd.phopencode.feature.chat.ui.status

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
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

/** Far more main thread steps than drawing a still status takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the status is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * The status comes to rest in a plain activity, the way the app's ActivityScenario tests open the
 * chat. An endless animation, such as a spinner for running work, would keep posting frames, and
 * Robolectric's `idle()` would never return. The looper is driven by hand as in the other screens'
 * idle tests: a due task runs, a task due within [REST_HORIZON] is jumped to, and the status is at
 * rest once nothing is due within the horizon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class StatusIdleTest {
    private fun assertComesToRest(status: ChatStatus) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { StatusBanner(state = StatusState(status), onIntent = {}) }
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
        fail("the main thread still had work due within $REST_HORIZON after $steps steps for $status")
    }

    /** How long until the looper's next task, or null when nothing is scheduled. */
    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    @Test
    fun `loading comes to rest`() = assertComesToRest(ChatStatus.Loading)

    @Test
    fun `running work comes to rest`() = assertComesToRest(ChatStatus.Running)

    @Test
    fun `a retry comes to rest`() =
        assertComesToRest(ChatStatus.Retrying(attempt = 2, nextAttemptAt = NEXT_TRY, message = PROVIDER_TEXT))

    @Test
    fun `a dropped connection comes to rest`() = assertComesToRest(ChatStatus.Disconnected)

    @Test
    fun `a failure comes to rest`() =
        assertComesToRest(ChatStatus.Failed(StructuredError(type = "provider.auth", message = ERROR_TEXT)))

    @Test
    fun `an idle session comes to rest`() = assertComesToRest(ChatStatus.Idle)

    @Test
    fun `a blocking status comes to rest`() {
        assertComesToRest(ChatStatus.NotConnected)
        assertComesToRest(ChatStatus.OtherServer)
        assertComesToRest(ChatStatus.Unavailable(ChatFailure.Unreachable))
    }
}
