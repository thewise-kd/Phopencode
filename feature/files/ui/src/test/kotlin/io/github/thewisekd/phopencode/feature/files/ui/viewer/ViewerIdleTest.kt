package io.github.thewisekd.phopencode.feature.files.ui.viewer

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
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
 * The viewer comes to rest in a plain activity in every state, the way the app's ActivityScenario
 * tests open it. An endless animation, such as a spinner while loading or a blinking cursor in the
 * code view, would keep posting frames, and Robolectric's `idle()` would never return. The looper is
 * driven by hand as in the other screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerIdleTest {
    private fun assertComesToRest(state: ViewerState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { ViewerScreen(routeOf(state.path, state.line), state, onIntent = {}) }
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
    fun `a loading viewer comes to rest`() = assertComesToRest(ViewerState(path = CODE_PATH, line = 3))

    @Test
    fun `code comes to rest`() = assertComesToRest(CODE.copy(line = 3))

    @Test
    fun `refreshing code comes to rest`() = assertComesToRest(CODE.copy(loading = true))

    @Test
    fun `rendered Markdown comes to rest`() = assertComesToRest(MARKDOWN)

    @Test
    fun `raw Markdown comes to rest`() = assertComesToRest(MARKDOWN.copy(mode = ViewerMode.Raw))

    @Test
    fun `malformed text comes to rest`() = assertComesToRest(MALFORMED)

    @Test
    fun `an image comes to rest`() = assertComesToRest(IMAGE)

    @Test
    fun `a binary file comes to rest`() = assertComesToRest(loaded("bin/app", ViewerContent.Binary(2_048)))

    @Test
    fun `a large file comes to rest`() = assertComesToRest(
        loaded("logs/app.log", ViewerContent.TooLarge(sizeBytes = 3_145_728, limitBytes = 1_048_576, canOpenAnyway = true)),
    )

    @Test
    fun `a file above the hard limit comes to rest`() = assertComesToRest(
        loaded("logs/app.log", ViewerContent.TooLarge(sizeBytes = 104_857_600, limitBytes = 10_485_760, canOpenAnyway = false)),
    )

    @Test
    fun `an error comes to rest`() =
        assertComesToRest(ViewerState(path = CODE_PATH, loading = false, error = FilesFailure.Unreachable))
}
