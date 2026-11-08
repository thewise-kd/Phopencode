package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
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
 * The skill screens come to rest in a plain activity in every state they draw, not only the loading
 * ones the feature's idle test covers: an endless animation would keep Robolectric's `idle()` in the
 * app's tests from returning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SkillsIdleTest {
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

    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    @Test
    fun `a listed, an empty and a failed skill list come to rest`() {
        assertComesToRest { SkillsScreen(CatalogRoute.Skills, listedSkills, onIntent = {}) }
        assertComesToRest { SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false), onIntent = {}) }
        assertComesToRest {
            SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false, error = CatalogFailure.Unreachable), onIntent = {})
        }
    }

    @Test
    fun `a loaded and a failed skill come to rest`() {
        assertComesToRest { SkillDetailScreen(CatalogRoute.SkillDetail(review.id), loadedSkill, onIntent = {}) }
        assertComesToRest {
            SkillDetailScreen(
                CatalogRoute.SkillDetail(review.id),
                SkillDetailState(skillId = review.id, loading = false, error = CatalogFailure.NotFound),
                onIntent = {},
            )
        }
    }
}
