package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSkillDetailPresenterTest {
    private val server = TestServer()
    private val catalog = RecordingSkillCatalog(DefaultSkillCatalog(server.connection))

    private val review = skill(
        "review",
        name = "Code Review",
        description = "Reads a diff",
        path = "/home/ayse/.config/opencode/skill/review/SKILL.md",
    )
    private val deploy = skill("deploy", path = "/srv/project/.opencode/skill/deploy/SKILL.md")

    private fun TestScope.presenter(skillId: String = "review") =
        DefaultSkillDetailPresenter(skillId = skillId, skills = catalog, scope = backgroundScope)

    private fun TestScope.events(presenter: DefaultSkillDetailPresenter, count: Int): List<SkillDetailEvent> {
        val events = mutableListOf<SkillDetailEvent>()
        val collecting = launch { presenter.events.take(count).toList(events) }
        runCurrent()
        collecting.cancel()
        return events
    }

    @Test
    fun `it shows the skill with its text and shortened source path`() = runTest {
        server.connect().catalog.setSkills(listOf(deploy, review))
        val presenter = presenter()
        assertEquals(SkillDetailState(skillId = "review"), presenter.state.value)

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(review, state.skill)
        assertEquals("# Code Review", state.skill?.content)
        assertEquals("~/.config/opencode/skill/review/SKILL.md", state.sourcePath)
        assertEquals("review", state.skillId)
    }

    @Test
    fun `a project skill shows its path as it is`() = runTest {
        server.connect().catalog.setSkills(listOf(deploy, review))
        val presenter = presenter("deploy")
        runCurrent()

        assertEquals("/srv/project/.opencode/skill/deploy/SKILL.md", presenter.state.value.sourcePath)
    }

    @Test
    fun `the list and its details share one read`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        DefaultSkillsPresenter(skills = catalog, scope = backgroundScope).onIntent(SkillsIntent.Search("x"))
        runCurrent()
        val presenter = presenter()
        runCurrent()

        assertEquals(review, presenter.state.value.skill)
        assertEquals(1, client.skillReads())
    }

    @Test
    fun `a skill the server does not list is not found`() = runTest {
        server.connect().catalog.setSkills(listOf(deploy))
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(CatalogFailure.NotFound, state.error)
        assertNull(state.skill)
        assertNull(state.sourcePath)
    }

    @Test
    fun `a retry after not found reads the server again`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(deploy))
        val presenter = presenter()
        runCurrent()

        client.catalog.setSkills(listOf(deploy, review))
        presenter.onIntent(SkillDetailIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertEquals(review, presenter.state.value.skill)
        assertEquals(listOf(false, true), catalog.refreshes)
    }

    @Test
    fun `a failed read shows its failure and a retry reads again`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        client.control.failNext(ApiError.Unauthorized)
        val presenter = presenter()
        runCurrent()

        assertEquals(CatalogFailure.Unauthorized, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(SkillDetailIntent.Retry)
        runCurrent()

        assertEquals(review, presenter.state.value.skill)
        assertNull(presenter.state.value.error)
        assertEquals(2, client.skillReads())
    }

    @Test
    fun `without a connection it is not connected`() = runTest {
        val presenter = presenter()
        runCurrent()

        assertEquals(CatalogFailure.NotConnected, presenter.state.value.error)
    }

    @Test
    fun `a retry while loading asks nothing more`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        client.control.stall()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillDetailIntent.Retry)
        runCurrent()
        client.control.resume()
        runCurrent()

        assertEquals(review, presenter.state.value.skill)
        assertEquals(listOf(false), catalog.refreshes)
    }

    @Test
    fun `using the skill starts a new session with it once loaded, never before`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        client.control.stall()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillDetailIntent.UseInNewSession)
        assertEquals(emptyList(), events(presenter, 1))

        client.control.resume()
        runCurrent()
        presenter.onIntent(SkillDetailIntent.UseInNewSession)

        assertEquals(listOf<SkillDetailEvent>(SkillDetailEvent.StartSession("review")), events(presenter, 2))
    }

    @Test
    fun `a skill not found starts no session`() = runTest {
        server.connect().catalog.setSkills(listOf(deploy))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillDetailIntent.UseInNewSession)

        assertEquals(emptyList(), events(presenter, 1))
    }

    @Test
    fun `close is an event`() = runTest {
        val presenter = presenter()
        presenter.onIntent(SkillDetailIntent.Close)

        assertEquals(listOf<SkillDetailEvent>(SkillDetailEvent.Close), events(presenter, 1))
    }

    @Test
    fun `the state prints neither the text nor the path`() = runTest {
        server.connect().catalog.setSkills(listOf(review))
        val presenter = presenter()
        runCurrent()

        val printed = presenter.state.value.toString()
        assertFalse("ayse" in printed)
        assertFalse("SKILL.md" in printed)
        assertFalse("Code Review" in printed)
    }
}
