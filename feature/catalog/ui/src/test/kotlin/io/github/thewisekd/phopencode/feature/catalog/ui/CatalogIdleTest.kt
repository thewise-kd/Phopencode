package io.github.thewisekd.phopencode.feature.catalog.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.AgentDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.CommandDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.ModelDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.RegistryScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillsScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.status.StatusScreen
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
 * Every catalog screen comes to rest in a plain activity, the way the app's ActivityScenario tests
 * open them, while loading and while refreshing. An endless animation, such as an indeterminate
 * spinner, would keep posting frames, and Robolectric's `idle()` would never return. The looper is
 * driven by hand as in the other features' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class CatalogIdleTest {
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
    fun `a loading and a refreshing skill list come to rest`() {
        assertComesToRest { SkillsScreen(CatalogRoute.Skills, SkillsState(), onIntent = {}) }
        assertComesToRest { SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false, refreshing = true), onIntent = {}) }
    }

    @Test
    fun `a loading skill comes to rest`() {
        assertComesToRest { SkillDetailScreen(CatalogRoute.SkillDetail("review"), SkillDetailState(skillId = "review"), onIntent = {}) }
    }

    @Test
    fun `a loading registry comes to rest on every tab`() {
        RegistryTab.entries.forEach { tab ->
            assertComesToRest { RegistryScreen(CatalogRoute.Registry, RegistryState(tab = tab), onIntent = {}) }
        }
    }

    @Test
    fun `loading details come to rest`() {
        assertComesToRest { CommandDetailScreen(CatalogRoute.CommandDetail("review"), CommandDetailState(name = "review"), onIntent = {}) }
        assertComesToRest { AgentDetailScreen(CatalogRoute.AgentDetail("build"), AgentDetailState(agentId = "build"), onIntent = {}) }
        assertComesToRest {
            ModelDetailScreen(
                CatalogRoute.ModelDetail("provider", "large"),
                ModelDetailState(providerId = "provider", modelId = "large"),
                onIntent = {},
            )
        }
    }

    @Test
    fun `a loading and a reloading status come to rest`() {
        assertComesToRest { StatusScreen(CatalogRoute.Status, StatusState(), onIntent = {}) }
        val reloading = StatusState(mcp = CatalogList(loading = true), plugins = CatalogList(loading = false))
        assertComesToRest { StatusScreen(CatalogRoute.Status, reloading, onIntent = {}) }
    }
}
