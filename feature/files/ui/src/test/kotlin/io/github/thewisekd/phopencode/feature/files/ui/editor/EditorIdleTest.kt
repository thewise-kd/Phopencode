package io.github.thewisekd.phopencode.feature.files.ui.editor

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
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
 * The editor comes to rest in a plain activity in every state, dialogs included, the way the app's
 * ActivityScenario tests open it. An endless animation, such as a spinner while saving or a blinking
 * cursor in the code view, would keep posting frames, and Robolectric's `idle()` would never return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EditorIdleTest {
    private fun assertComesToRest(state: EditorState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { EditorScreen(ROUTE, state, onIntent = {}) }
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
    fun `a loading editor comes to rest`() = assertComesToRest(LOADING)

    @Test
    fun `a clean file comes to rest`() = assertComesToRest(CLEAN)

    @Test
    fun `a dirty file comes to rest`() = assertComesToRest(DIRTY)

    @Test
    fun `saving comes to rest`() = assertComesToRest(SAVING)

    @Test
    fun `a conflict comes to rest`() = assertComesToRest(DIRTY.copy(conflict = true))

    @Test
    fun `the leave question comes to rest`() = assertComesToRest(DIRTY.copy(confirmingLeave = true))

    @Test
    fun `a binary file comes to rest`() = assertComesToRest(CLEAN.copy(text = "", blocked = EditorBlock.Binary))

    @Test
    fun `a large file comes to rest`() = assertComesToRest(CLEAN.copy(text = "", blocked = EditorBlock.TooLarge))

    @Test
    fun `a malformed file comes to rest`() = assertComesToRest(CLEAN.copy(text = "", blocked = EditorBlock.Malformed))

    @Test
    fun `a failed load comes to rest`() = assertComesToRest(CLEAN.copy(text = "", loadError = FilesFailure.Unreachable))

    @Test
    fun `a failed save comes to rest`() = assertComesToRest(DIRTY.copy(saveError = FilesFailure.ServerFailed))
}
