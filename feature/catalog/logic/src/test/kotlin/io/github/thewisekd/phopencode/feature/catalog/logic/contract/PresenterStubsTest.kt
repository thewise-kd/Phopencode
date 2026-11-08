package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultAgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultCommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultRegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultRegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.status.DefaultStatusPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Every presenter and cache is built with the constructor the contract fixes, as `CatalogModule`
 * builds it, and every presenter shows its screen's initial state before anything has run. The logic
 * tasks fill them in; these hold for the filled-in ones too. The connection fails every call: a
 * presenter may not ask the server before its scope runs.
 */
class PresenterStubsTest {
    private val connection = object : CatalogConnection {
        override val generations: Flow<Long?> = flowOf(null)

        override suspend fun <T> call(block: suspend (CatalogApi, Long) -> T): T = error("Not called before the scope runs")
    }

    private val skills = object : SkillCatalog {
        override suspend fun skills(refresh: Boolean): List<Skill> = error("Not called before the scope runs")
    }

    private val registry = object : RegistryCatalog {
        override suspend fun commands(refresh: Boolean): List<Command> = error("Not called before the scope runs")

        override suspend fun agents(refresh: Boolean): List<Agent> = error("Not called before the scope runs")

        override suspend fun models(refresh: Boolean): ModelCatalog = error("Not called before the scope runs")
    }

    @Test
    fun `the skill list starts loading with no search`() = runTest {
        val presenter: SkillsPresenter = DefaultSkillsPresenter(skills = skills, scope = backgroundScope)

        assertEquals(SkillsState(), presenter.state.value)
    }

    @Test
    fun `a skill starts loading`() = runTest {
        val presenter: SkillDetailPresenter =
            DefaultSkillDetailPresenter(skillId = "review", skills = skills, scope = backgroundScope)

        assertEquals(SkillDetailState(skillId = "review"), presenter.state.value)
    }

    @Test
    fun `the registry starts on the command tab, every list loading`() = runTest {
        val presenter: RegistryPresenter = DefaultRegistryPresenter(registry = registry, scope = backgroundScope)

        assertEquals(RegistryState(), presenter.state.value)
    }

    @Test
    fun `command, agent and model details start loading`() = runTest {
        val command: CommandDetailPresenter =
            DefaultCommandDetailPresenter(name = "review", registry = registry, scope = backgroundScope)
        val agent: AgentDetailPresenter = DefaultAgentDetailPresenter(agentId = "build", registry = registry, scope = backgroundScope)
        val model: ModelDetailPresenter = DefaultModelDetailPresenter(
            providerId = "provider",
            modelId = "model/large",
            registry = registry,
            scope = backgroundScope,
        )

        assertEquals(CommandDetailState(name = "review"), command.state.value)
        assertEquals(AgentDetailState(agentId = "build"), agent.state.value)
        assertEquals(ModelDetailState(providerId = "provider", modelId = "model/large"), model.state.value)
    }

    @Test
    fun `the status starts loading both lists`() = runTest {
        val presenter: StatusPresenter = DefaultStatusPresenter(connection = connection, scope = backgroundScope)

        assertEquals(StatusState(), presenter.state.value)
    }

    @Test
    fun `the caches are built on the connection`() {
        val skillCatalog: SkillCatalog = DefaultSkillCatalog(connection = connection)
        val registryCatalog: RegistryCatalog = DefaultRegistryCatalog(connection = connection)

        assertEquals("DefaultSkillCatalog", skillCatalog.toString())
        assertEquals("DefaultRegistryCatalog", registryCatalog.toString())
    }
}
