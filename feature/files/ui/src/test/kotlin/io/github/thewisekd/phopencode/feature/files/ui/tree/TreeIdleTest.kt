package io.github.thewisekd.phopencode.feature.files.ui.tree

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
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
 * The tree comes to rest in a plain activity, the way the app's ActivityScenario tests open it, in
 * every state it can be in: loading, reloading, listing a large folder, empty and failed. An endless
 * animation, such as an indeterminate spinner while a folder loads, would keep posting frames, and
 * Robolectric's `idle()` would never return. The looper is driven by hand as in `FilesIdleTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TreeIdleTest {
    private fun assertComesToRest(state: TreeState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { PhopencodeTheme { TreeScreen(route = route, state = state, onIntent = {}) } }
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
    fun `the first load comes to rest`() {
        assertComesToRest(TreeState(directory = ROOT))
    }

    @Test
    fun `a reload below the project's folder comes to rest`() {
        assertComesToRest(listing.copy(folder = MAIN, refreshing = true))
    }

    @Test
    fun `opening another folder over the old rows comes to rest`() {
        assertComesToRest(listing.copy(folder = SRC, loading = true))
    }

    @Test
    fun `a large folder comes to rest`() {
        assertComesToRest(largeFolder(5_000))
    }

    @Test
    fun `an empty folder comes to rest`() {
        assertComesToRest(TreeState(directory = ROOT, loading = false))
    }

    @Test
    fun `a failure comes to rest`() {
        assertComesToRest(TreeState(directory = ROOT, loading = false, error = FilesFailure.Unreachable))
    }
}
