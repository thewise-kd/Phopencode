package io.github.thewisekd.phopencode.feature.catalog.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SKILL = "gözden geçir"
private const val PROVIDER = "provider"
private const val MODEL = "model/large"

/**
 * Each screen keeps, on its root, the `placeholder:<route>` tag the app's navigation test looks for,
 * with the screen's initial state. The screen tasks fill the screens in; this holds for them too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ScreenStubsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the skill list keeps its route's tag`() {
        val route = CatalogRoute.Skills
        compose.setContent { PhopencodeTheme { SkillsScreen(route, SkillsState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a skill keeps its route's tag`() {
        val route = CatalogRoute.SkillDetail(SKILL)
        compose.setContent { PhopencodeTheme { SkillDetailScreen(route, SkillDetailState(skillId = SKILL), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the registry keeps its route's tag`() {
        val route = CatalogRoute.Registry
        compose.setContent { PhopencodeTheme { RegistryScreen(route, RegistryState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a command keeps its route's tag`() {
        val route = CatalogRoute.CommandDetail("review")
        compose.setContent { PhopencodeTheme { CommandDetailScreen(route, CommandDetailState(name = "review"), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `an agent keeps its route's tag`() {
        val route = CatalogRoute.AgentDetail("build")
        compose.setContent { PhopencodeTheme { AgentDetailScreen(route, AgentDetailState(agentId = "build"), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a model keeps its route's tag`() {
        val route = CatalogRoute.ModelDetail(PROVIDER, MODEL)
        compose.setContent {
            PhopencodeTheme {
                ModelDetailScreen(route, ModelDetailState(providerId = PROVIDER, modelId = MODEL), onIntent = {})
            }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the status keeps its route's tag`() {
        val route = CatalogRoute.Status
        compose.setContent { PhopencodeTheme { StatusScreen(route, StatusState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
