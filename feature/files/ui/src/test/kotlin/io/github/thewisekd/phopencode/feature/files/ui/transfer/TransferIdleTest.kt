package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
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
 * The transfer screen comes to rest in a plain activity in every state, the overwrite prompt
 * included, the way the app's ActivityScenario tests open it. An endless animation, such as an
 * indeterminate progress bar for a job of unknown size, would keep posting frames, and
 * Robolectric's `idle()` would never return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TransferIdleTest {
    private fun assertComesToRest(state: TransferState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { TransferScreen(ROUTE, state, onIntent = {}) }
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
    fun `no transfers come to rest`() = assertComesToRest(EMPTY)

    @Test
    fun `a queued job comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(QUEUED)))

    @Test
    fun `a running job of known size comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(UPLOADING)))

    @Test
    fun `a running job of unknown size comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(DOWNLOADING_UNKNOWN)))

    @Test
    fun `the overwrite prompt comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(AWAITING)))

    @Test
    fun `finished jobs come to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(UPLOADED, DOWNLOADED)))

    @Test
    fun `a cancelled job comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(CANCELLED)))

    @Test
    fun `a failed job comes to rest`() = assertComesToRest(EMPTY.copy(jobs = listOf(FAILED)))

    @Test
    fun `a pending download comes to rest`() = assertComesToRest(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))

    @Test
    fun `every kind of job together comes to rest`() = assertComesToRest(
        EMPTY.copy(jobs = listOf(QUEUED, UPLOADING, DOWNLOADING_UNKNOWN, AWAITING, UPLOADED, DOWNLOADED, CANCELLED, FAILED)),
    )
}
