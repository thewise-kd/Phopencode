package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
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
 * The screen comes to rest in a plain activity, the way the app's ActivityScenario tests open it,
 * without Compose's test clock. An endless animation would keep posting frames, and Robolectric's
 * `idle()` would then never return: those tests would hang instead of fail.
 *
 * So the looper is driven by hand, one step at a time: a task due now is run; otherwise, when the next
 * task is due within [REST_HORIZON], such as a frame about 16 ms ahead, the clock jumps to it. The
 * screen is at rest only when nothing is due now or within the horizon. A screen that never rests
 * fails within [STEP_LIMIT] steps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ServerListIdleTest {
    private fun assertComesToRest(state: ServerListState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { ServerListScreen(route = ConnectRoute.ServerList, state = state, onIntent = {}) }
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
    fun `the loading screen comes to rest`() {
        assertComesToRest(ServerListState(loading = true))
    }

    @Test
    fun `the list comes to rest`() {
        assertComesToRest(ServerListState(loading = false, servers = listOf(firstServer, secondServer)))
    }

    @Test
    fun `the empty state comes to rest`() {
        assertComesToRest(ServerListState(loading = false))
    }
}
