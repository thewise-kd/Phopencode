package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSkillsPresenterTest {
    private val server = TestServer()
    private val catalog = RecordingSkillCatalog(DefaultSkillCatalog(server.connection))

    private val review = skill("review", name = "Code Review", description = "Reads a diff and comments")
    private val deploy = skill("deploy", name = "Deploy", description = "Ships the İstanbul build")
    private val notes = skill("release-notes", name = "Release notes")

    private fun TestScope.presenter() = DefaultSkillsPresenter(skills = catalog, scope = backgroundScope)

    private fun SkillsState.shownIds() = shown.map { it.id }

    @Test
    fun `it shows the initial state, then the list in the server's order`() = runTest {
        server.connect().catalog.setSkills(listOf(review, deploy, notes))
        val presenter = presenter()
        assertEquals(SkillsState(), presenter.state.value)

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf(review, deploy, notes), state.skills)
        assertEquals(listOf(review, deploy, notes), state.shown)
        assertEquals(listOf(false), catalog.refreshes)
    }

    @Test
    fun `a server without skills reads as empty`() = runTest {
        server.connect()
        val presenter = presenter()
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
    }

    @Test
    fun `search matches id, name or description, ignoring case`() = runTest {
        server.connect().catalog.setSkills(listOf(review, deploy, notes))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillsIntent.Search("REVIEW"))
        assertEquals(listOf("review"), presenter.state.value.shownIds())
        assertEquals("REVIEW", presenter.state.value.query)

        presenter.onIntent(SkillsIntent.Search("notes"))
        assertEquals(listOf("release-notes"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("diff"))
        assertEquals(listOf("review"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("istanbul"))
        assertEquals(listOf("deploy"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("e"))
        assertEquals(listOf("review", "deploy", "release-notes"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("nothing like it"))
        assertEquals(emptyList(), presenter.state.value.shownIds())
        assertEquals(3, presenter.state.value.skills.size)
        assertFalse(presenter.state.value.isEmpty, "a search without matches is not an empty server")
    }

    @Test
    fun `every word of the search must match, and a blank search shows all`() = runTest {
        server.connect().catalog.setSkills(listOf(review, deploy, notes))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillsIntent.Search("  code   comments "))
        assertEquals(listOf("review"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("code deploy"))
        assertEquals(emptyList(), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("   "))
        assertEquals(listOf("review", "deploy", "release-notes"), presenter.state.value.shownIds())
    }

    @Test
    fun `a search typed while loading applies to the list once read`() = runTest {
        server.connect().catalog.setSkills(listOf(review, deploy, notes))
        val presenter = presenter()

        presenter.onIntent(SkillsIntent.Search("deploy"))
        assertEquals("deploy", presenter.state.value.query)
        runCurrent()

        assertEquals(listOf("deploy"), presenter.state.value.shownIds())
    }

    @Test
    fun `a failed read shows its failure, and a reload reads again`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        client.control.failNext(ApiError.Network)
        val presenter = presenter()
        runCurrent()

        assertEquals(CatalogFailure.Unreachable, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        assertFalse(presenter.state.value.isEmpty)

        presenter.onIntent(SkillsIntent.Reload)
        assertTrue(presenter.state.value.loading, "nothing read yet: the reload shows as loading")
        assertNull(presenter.state.value.error)
        runCurrent()

        assertEquals(listOf(review), presenter.state.value.skills)
        assertNull(presenter.state.value.error)
        assertEquals(listOf(false, true), catalog.refreshes)
    }

    @Test
    fun `a reload refreshes with the list on screen, and a failure keeps it`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(SkillsIntent.Search("review"))

        client.catalog.setSkills(listOf(review, deploy))
        client.control.stall()
        presenter.onIntent(SkillsIntent.Reload)
        runCurrent()
        assertTrue(presenter.state.value.refreshing)
        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(review), presenter.state.value.skills)
        client.control.resume()
        runCurrent()

        assertFalse(presenter.state.value.refreshing)
        assertEquals(listOf(review, deploy), presenter.state.value.skills)
        assertEquals(listOf("review"), presenter.state.value.shownIds(), "the search stays")

        client.control.failNext(ApiError.Server(500, "boom"))
        presenter.onIntent(SkillsIntent.Reload)
        runCurrent()

        assertEquals(CatalogFailure.ServerFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.refreshing)
        assertEquals(listOf(review, deploy), presenter.state.value.skills)
        assertEquals(3, client.skillReads())
    }

    @Test
    fun `a refresh reads through the kept list`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()

        assertEquals(listOf(false, false), catalog.refreshes)
        assertEquals(1, client.skillReads())
        assertFalse(presenter.state.value.refreshing)
    }

    @Test
    fun `a refresh after a new connection shows the new server's list`() = runTest {
        server.connect(generation = 1).catalog.setSkills(listOf(review))
        val presenter = presenter()
        runCurrent()

        server.connect(generation = 2).catalog.setSkills(listOf(deploy))
        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()

        assertEquals(listOf(deploy), presenter.state.value.skills)
    }

    @Test
    fun `a refresh after a new connection does not wait on the old connection's read`() = runTest {
        val older = server.connect(generation = 1)
        older.catalog.setSkills(listOf(review))
        older.control.stall()
        val presenter = presenter()
        runCurrent()
        older.control.awaitRunningCalls(1)

        server.connect(generation = 2).catalog.setSkills(listOf(deploy))
        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(deploy), presenter.state.value.skills)

        older.control.resume()
        runCurrent()
        assertEquals(listOf(deploy), presenter.state.value.skills, "the old connection's late answer is not shown")
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a refresh during a load of the same connection asks nothing more`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        client.control.stall()
        val presenter = presenter()
        runCurrent()
        client.control.awaitRunningCalls(1)

        presenter.onIntent(SkillsIntent.Refresh)
        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()
        client.control.resume()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(review), presenter.state.value.skills)
        assertEquals(1, client.skillReads())
    }

    @Test
    fun `a reload while one runs asks once`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(review))
        val presenter = presenter()
        runCurrent()

        client.control.stall()
        presenter.onIntent(SkillsIntent.Reload)
        presenter.onIntent(SkillsIntent.Reload)
        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()
        client.control.resume()
        runCurrent()

        assertEquals(2, client.skillReads())
        assertFalse(presenter.state.value.refreshing)
    }

    /**
     * A catalog whose reads wait for the test. A read whose caller is cancelled still answers, with
     * [onCancel], as one that had finished just as the cancel came would.
     */
    private class GatedCatalog(private val onCancel: () -> List<Skill>) : SkillCatalog {
        val gates = mutableListOf<CompletableDeferred<List<Skill>>>()

        override suspend fun skills(refresh: Boolean): List<Skill> {
            val gate = CompletableDeferred<List<Skill>>().also { gates += it }
            return try {
                gate.await()
            } catch (e: CancellationException) {
                onCancel()
            }
        }
    }

    @Test
    fun `a load a reload replaced shows nothing, even if it answers`() = runTest {
        val gated = GatedCatalog(onCancel = { listOf(deploy) })
        val presenter = DefaultSkillsPresenter(skills = gated, scope = backgroundScope)
        runCurrent()

        presenter.onIntent(SkillsIntent.Reload)
        runCurrent()

        assertTrue(presenter.state.value.loading, "the reload still runs")
        assertEquals(emptyList(), presenter.state.value.skills)
        assertEquals(2, gated.gates.size)

        gated.gates[1].complete(listOf(review))
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(review), presenter.state.value.skills)
    }

    @Test
    fun `a load that answers the moment it is cancelled never reaches the state`() = runTest {
        val gated = GatedCatalog(onCancel = { listOf(deploy) })
        // Unconfined: the cancelled load resumes at once, inside the reload's own onIntent.
        val presenter = DefaultSkillsPresenter(
            skills = gated,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val seen = mutableListOf<SkillsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { presenter.state.collect { seen += it } }
        runCurrent()
        assertEquals(1, gated.gates.size)

        presenter.onIntent(SkillsIntent.Reload)
        runCurrent()
        gated.gates.last().complete(listOf(review))
        runCurrent()

        assertEquals(listOf(review), presenter.state.value.skills)
        assertFalse(presenter.state.value.loading)
        assertTrue(seen.none { deploy in it.skills }, "the cancelled load's answer was shown")
    }

    @Test
    fun `a search of only whitespace, unicode spaces included, shows every skill`() = runTest {
        server.connect().catalog.setSkills(listOf(review, deploy, notes))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(SkillsIntent.Search(" "))
        assertEquals(listOf("review", "deploy", "release-notes"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("  \t  "))
        assertEquals(listOf("review", "deploy", "release-notes"), presenter.state.value.shownIds())

        presenter.onIntent(SkillsIntent.Search("code comments "))
        assertEquals(listOf("review"), presenter.state.value.shownIds())
    }

    @Test
    fun `a refresh a reload replaced cannot end the reload's refreshing`() = runTest {
        val gated = GatedCatalog(onCancel = { throw ApiException(ApiError.Network) })
        val presenter = DefaultSkillsPresenter(skills = gated, scope = backgroundScope)
        runCurrent()
        gated.gates[0].complete(listOf(review))
        runCurrent()

        presenter.onIntent(SkillsIntent.Refresh)
        runCurrent()
        presenter.onIntent(SkillsIntent.Reload)
        runCurrent()

        assertTrue(presenter.state.value.refreshing)
        assertNull(presenter.state.value.error)
        assertEquals(3, gated.gates.size)

        gated.gates[2].complete(listOf(review, deploy))
        runCurrent()

        assertFalse(presenter.state.value.refreshing)
        assertEquals(listOf(review, deploy), presenter.state.value.skills)
    }

    @Test
    fun `rows and close become events, each once`() = runTest {
        server.connect()
        val presenter = presenter()
        presenter.onIntent(SkillsIntent.OpenSkill("review"))
        presenter.onIntent(SkillsIntent.Close)

        val events = mutableListOf<SkillsEvent>()
        val collecting = launch { presenter.events.take(2).toList(events) }
        collecting.join()

        assertEquals(listOf(SkillsEvent.OpenSkill("review"), SkillsEvent.Close), events)
    }

    @Test
    fun `nothing is asked before the scope runs`() = runTest {
        server.connect()
        presenter()

        assertEquals(emptyList(), catalog.refreshes)
    }
}
