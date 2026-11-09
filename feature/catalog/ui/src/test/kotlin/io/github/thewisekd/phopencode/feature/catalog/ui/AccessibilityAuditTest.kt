package io.github.thewisekd.phopencode.feature.catalog.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
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
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.agentShown
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.commandShown
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.modelShown
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.registry
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillsScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.listedSkills
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.loadedSkill
import io.github.thewisekd.phopencode.feature.catalog.ui.status.StatusScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.status.allMcp
import io.github.thewisekd.phopencode.feature.catalog.ui.status.allPlugins
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every catalog screen, loaded, empty, failed and loading, at the largest font: every control is named
 * and big enough to tap, and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = CatalogFailure.Unreachable

    private fun audit(content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible()
    }

    @Test
    fun `the skills list loaded`() = audit { SkillsScreen(CatalogRoute.Skills, listedSkills, {}) }

    @Test
    fun `the skills list empty`() = audit { SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false), {}) }

    @Test
    fun `the skills list failed`() {
        audit { SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false, error = failure), {}) }
        compose.assertErrorAnnounced(R.string.catalog_skills_error_unreachable)
    }

    @Test
    fun `a skill loaded`() = audit { SkillDetailScreen(CatalogRoute.SkillDetail(loadedSkill.skillId), loadedSkill, {}) }

    @Test
    fun `a skill failed`() {
        val state = SkillDetailState(skillId = loadedSkill.skillId, loading = false, error = failure)
        audit { SkillDetailScreen(CatalogRoute.SkillDetail(state.skillId), state, {}) }
        compose.assertErrorAnnounced(R.string.catalog_skills_error_unreachable)
    }

    private fun registryTest(state: RegistryState) = audit { RegistryScreen(CatalogRoute.Registry, state, {}) }

    @Test
    fun `the registry's commands`() = registryTest(registry.copy(tab = RegistryTab.Commands))

    @Test
    fun `the registry's agents`() = registryTest(registry.copy(tab = RegistryTab.Agents))

    @Test
    fun `the registry's models`() = registryTest(registry.copy(tab = RegistryTab.Models))

    @Test
    fun `the registry empty`() = registryTest(RegistryState(commands = CatalogList(loading = false)))

    @Test
    fun `the registry failed`() {
        registryTest(RegistryState(commands = CatalogList(loading = false, error = failure)))
        compose.assertErrorAnnounced(R.string.catalog_registry_error_unreachable)
    }

    @Test
    fun `a command loaded`() = audit { CommandDetailScreen(CatalogRoute.CommandDetail(commandShown.name), commandShown, {}) }

    @Test
    fun `a command failed`() {
        val state = CommandDetailState(name = commandShown.name, loading = false, error = failure)
        audit { CommandDetailScreen(CatalogRoute.CommandDetail(state.name), state, {}) }
        compose.assertErrorAnnounced(R.string.catalog_registry_error_unreachable)
    }

    @Test
    fun `an agent loaded`() = audit { AgentDetailScreen(CatalogRoute.AgentDetail(agentShown.agentId), agentShown, {}) }

    @Test
    fun `an agent failed`() {
        val state = AgentDetailState(agentId = agentShown.agentId, loading = false, error = failure)
        audit { AgentDetailScreen(CatalogRoute.AgentDetail(state.agentId), state, {}) }
        compose.assertErrorAnnounced(R.string.catalog_registry_error_unreachable)
    }

    @Test
    fun `a model loaded`() =
        audit { ModelDetailScreen(CatalogRoute.ModelDetail(modelShown.providerId, modelShown.modelId), modelShown, {}) }

    @Test
    fun `a model failed`() {
        val state = ModelDetailState(providerId = "acme", modelId = "large", loading = false, error = failure)
        audit { ModelDetailScreen(CatalogRoute.ModelDetail(state.providerId, state.modelId), state, {}) }
        compose.assertErrorAnnounced(R.string.catalog_registry_error_unreachable)
    }

    @Test
    fun `the status loaded`() = audit {
        StatusScreen(
            CatalogRoute.Status,
            StatusState(CatalogList(loading = false, items = allMcp), CatalogList(loading = false, items = allPlugins)),
            {},
        )
    }

    @Test
    fun `the status empty`() = audit {
        StatusScreen(CatalogRoute.Status, StatusState(CatalogList(loading = false), CatalogList(loading = false)), {})
    }

    @Test
    fun `the status failed`() {
        audit {
            StatusScreen(
                CatalogRoute.Status,
                StatusState(CatalogList(loading = false, error = failure), CatalogList(loading = false, error = failure)),
                {},
            )
        }
        compose.assertErrorAnnounced(R.string.catalog_status_failure_unreachable)
    }
}
