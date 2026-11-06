package io.github.thewisekd.phopencode.feature.git.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.git.ui.branch.BranchesScreen
import io.github.thewisekd.phopencode.feature.git.ui.commit.CommitScreen
import io.github.thewisekd.phopencode.feature.git.ui.diff.DiffScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.CommitDetailScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.LogScreen
import io.github.thewisekd.phopencode.feature.git.ui.remote.RemoteScreen
import io.github.thewisekd.phopencode.feature.git.ui.status.StatusScreen
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

private const val ROOT = "/srv/çalışma alanı"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/**
 * Every git screen comes to rest in a plain activity, the way the app's ActivityScenario tests open
 * them, while loading and while busy. An endless animation, such as an indeterminate spinner while
 * loading, committing or pushing, would keep posting frames, and Robolectric's `idle()` would never
 * return. The looper is driven by hand as in the other features' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class GitIdleTest {
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
    fun `a loading status comes to rest`() {
        assertComesToRest { StatusScreen(GitRoute.Status(ROOT), StatusState(directory = ROOT), onIntent = {}) }
    }

    @Test
    fun `a refreshing status with a pending stage comes to rest`() {
        val state = StatusState(directory = ROOT, loading = false, refreshing = true, pending = setOf("a.kt"))
        assertComesToRest { StatusScreen(GitRoute.Status(ROOT), state, onIntent = {}) }
    }

    @Test
    fun `a loading diff comes to rest`() {
        val route = GitRoute.FileDiff(ROOT, "a.kt")
        assertComesToRest { DiffScreen(route, DiffState(target = DiffTarget.of(route)), onIntent = {}) }
    }

    @Test
    fun `a loading log and a log loading more come to rest`() {
        assertComesToRest { LogScreen(GitRoute.Log(ROOT), LogState(directory = ROOT), onIntent = {}) }
        assertComesToRest {
            LogScreen(GitRoute.Log(ROOT), LogState(directory = ROOT, loading = false, loadingMore = true), onIntent = {})
        }
    }

    @Test
    fun `loading commit details come to rest`() {
        assertComesToRest { CommitDetailScreen(GitRoute.CommitDetail(ROOT, HASH), CommitDetailState(commit = HASH), onIntent = {}) }
    }

    @Test
    fun `a committing screen comes to rest`() {
        val state = CommitState(directory = ROOT, loading = false, message = "Fix", busy = CommitOp.Commit)
        assertComesToRest { CommitScreen(GitRoute.Commit(ROOT), state, onIntent = {}) }
    }

    @Test
    fun `branches switching come to rest`() {
        assertComesToRest { BranchesScreen(GitRoute.Branches(ROOT), BranchesState(directory = ROOT), onIntent = {}) }
        val state = BranchesState(directory = ROOT, loading = false, busy = BranchOp.Switch)
        assertComesToRest { BranchesScreen(GitRoute.Branches(ROOT), state, onIntent = {}) }
    }

    @Test
    fun `a running push comes to rest`() {
        assertComesToRest { RemoteScreen(GitRoute.Remote(ROOT), RemoteState(directory = ROOT), onIntent = {}) }
        val state = RemoteState(directory = ROOT, loading = false, selected = "origin", running = RemoteOp.Push)
        assertComesToRest { RemoteScreen(GitRoute.Remote(ROOT), state, onIntent = {}) }
    }
}
