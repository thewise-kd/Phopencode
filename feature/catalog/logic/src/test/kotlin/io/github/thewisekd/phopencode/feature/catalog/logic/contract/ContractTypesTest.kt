package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secretPath = listOf("/home", "ayşe", ".config", "opencode", "skill", "gizli", "SKILL.md").joinToString("/")
    private val secretText = listOf("password", "=", "hunter", "2").joinToString("")
    private val secretQuery = listOf("gizli", "arama").joinToString(" ")
    private val secretUrl = listOf("https://", "user:", "pa55", "@", "mcp.example.com").joinToString("")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    private fun skill() = Skill(
        id = "review",
        name = "Review",
        description = secretText,
        path = secretPath,
        content = "# Review\n$secretText",
    )

    private fun agent() = Agent(
        id = "build",
        name = "Build",
        description = secretText,
        mode = AgentMode.Primary,
        permissions = listOf(PermissionRule(action = "edit", resource = secretPath, effect = PermissionEffect.Ask)),
    )

    @Test
    fun `failures map from every api error`() {
        assertEquals(CatalogFailure.Unauthorized, CatalogFailure.of(ApiError.Unauthorized))
        assertEquals(CatalogFailure.Rejected, CatalogFailure.of(ApiError.Forbidden))
        assertEquals(CatalogFailure.Rejected, CatalogFailure.of(ApiError.BadRequest(secretText)))
        assertEquals(CatalogFailure.NotFound, CatalogFailure.of(ApiError.NotFound))
        assertEquals(CatalogFailure.Rejected, CatalogFailure.of(ApiError.Conflict(secretText)))
        assertEquals(CatalogFailure.ServerFailed, CatalogFailure.of(ApiError.Server(500, secretText)))
        assertEquals(CatalogFailure.Unreachable, CatalogFailure.of(ApiError.Network))
        assertEquals(CatalogFailure.Unreachable, CatalogFailure.of(ApiError.Timeout))
        assertEquals(CatalogFailure.NotConnected, CatalogFailure.of(ApiError.Closed))
        assertEquals(CatalogFailure.Unknown, CatalogFailure.of(ApiError.VersionTooOld("1.0")))
        assertEquals(CatalogFailure.Unknown, CatalogFailure.of(ApiError.MalformedResponse))
        assertEquals(CatalogFailure.Unknown, CatalogFailure.of(ApiError.Unexpected(418, secretText)))
    }

    @Test
    fun `a list starts loading and prints counts only`() {
        val list = CatalogList<Skill>()
        assertTrue(list.loading)
        assertTrue(list.items.isEmpty())
        assertNull(list.error)
        assertFalse(list.isEmpty, "a loading list is not empty yet")

        val loaded = CatalogList(loading = false, items = listOf(skill()))
        assertMasked(loaded, secretPath, secretText)
        assertTrue(CatalogList<Skill>(loading = false).isEmpty)
        assertFalse(CatalogList<Skill>(loading = false, error = CatalogFailure.Unreachable).isEmpty)
    }

    @Test
    fun `skill states and intents print no path, text or query`() {
        val state = SkillsState(loading = false, skills = listOf(skill()), shown = listOf(skill()), query = secretQuery)
        assertMasked(state, secretPath, secretText, secretQuery)
        assertMasked(SkillsIntent.Search(secretQuery), secretQuery)

        val detail = SkillDetailState(skillId = "review", loading = false, skill = skill(), sourcePath = secretPath)
        assertMasked(detail, secretPath, secretText)
        assertTrue(SkillDetailState(skillId = "review").loading)
    }

    @Test
    fun `registry states print no description or permission resource`() {
        val state = RegistryState(
            commands = CatalogList(loading = false, items = listOf(Command("review", secretText))),
            agents = CatalogList(loading = false, items = listOf(agent())),
        )
        assertEquals(RegistryTab.Commands, state.tab)
        assertMasked(state, secretPath, secretText)
        assertMasked(CommandDetailState(name = "review", loading = false, command = Command("review", secretText)), secretText)
        assertMasked(AgentDetailState(agentId = "build", loading = false, agent = agent()), secretText, secretPath)
    }

    @Test
    fun `a model detail starts loading and knows whether it is the default`() {
        val model = Model(id = "large", providerId = "provider", name = "Large")
        val initial = ModelDetailState(providerId = "provider", modelId = "large")
        assertTrue(initial.loading)
        assertFalse(initial.isDefault)

        val loaded = ModelDetailState(
            providerId = "provider",
            modelId = "large",
            loading = false,
            model = model,
            providerName = "Provider",
            defaultModel = ModelRef("provider", "large"),
        )
        assertTrue(loaded.isDefault)
        assertFalse(loaded.copy(defaultModel = ModelRef("provider", "small")).isDefault)
    }

    @Test
    fun `the model catalog keeps providers and the default`() {
        val catalog = ModelCatalog(
            models = listOf(Model(id = "large", providerId = "provider", name = "Large")),
            providers = listOf(Provider(id = "provider", name = "Provider")),
            defaultModel = null,
        )
        assertEquals("Provider", catalog.providerName("provider"))
        assertNull(catalog.providerName("other"))
    }

    @Test
    fun `the status prints no mcp or plugin error`() {
        val state = StatusState(
            mcp = CatalogList(loading = false, items = listOf(McpServer("docs", McpStatus.Failed, error = secretUrl))),
            plugins = CatalogList(loading = false, items = listOf(Plugin("p", PluginStatus.Failed, error = secretPath))),
        )
        assertMasked(state, secretUrl, secretPath)
        assertTrue(StatusState().mcp.loading)
        assertTrue(StatusState().plugins.loading)
    }

    @Test
    fun `tabs are fixed`() {
        assertEquals(listOf(RegistryTab.Commands, RegistryTab.Agents, RegistryTab.Models), RegistryTab.entries)
    }
}
