package io.github.thewisekd.phopencode.feature.files.ui.search

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
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
 * The search comes to rest in a plain activity in every state, the way the app's ActivityScenario
 * tests open it. An endless animation, such as a spinner while searching, would keep posting frames,
 * and Robolectric's `idle()` would never return. The looper is driven by hand as in the other
 * screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SearchIdleTest {
    private fun assertComesToRest(state: SearchState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { SearchScreen(ROUTE, state, onIntent = {}) }
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
    fun `an untouched search comes to rest`() = assertComesToRest(IDLE)

    @Test
    fun `a typed query comes to rest`() = assertComesToRest(TYPED.copy(mode = SearchMode.Content))

    @Test
    fun `a running search comes to rest`() = assertComesToRest(SEARCHING)

    @Test
    fun `a running search over old results comes to rest`() = assertComesToRest(CONTENT.copy(searching = true))

    @Test
    fun `name hits come to rest`() = assertComesToRest(NAMES)

    @Test
    fun `content hits come to rest`() = assertComesToRest(CONTENT)

    @Test
    fun `content hits found with grep come to rest`() = assertComesToRest(CONTENT.copy(tool = ContentSearchTool.Grep))

    @Test
    fun `a truncated search comes to rest`() = assertComesToRest(manyHits(200).copy(truncated = true))

    @Test
    fun `an empty search comes to rest`() = assertComesToRest(EMPTY)

    @Test
    fun `an error with retry comes to rest`() =
        assertComesToRest(TYPED.copy(searched = true, error = FilesFailure.Unreachable))

    @Test
    fun `an error without retry comes to rest`() =
        assertComesToRest(TYPED.copy(mode = SearchMode.Content, searched = true, error = FilesFailure.Rejected))
}
