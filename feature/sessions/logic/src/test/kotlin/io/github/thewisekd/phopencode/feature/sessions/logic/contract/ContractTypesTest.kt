package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secretTitle = listOf("deploy", "with", "token", "42").joinToString("-")
    private val sessionId = SessionId("ses_1")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    private fun row(
        title: String? = "Fix the build",
        status: SessionBadge = SessionBadge.Idle,
        usage: TokenUsage? = null,
    ) = SessionRow(id = sessionId, title = title, directory = "/srv/app", status = status, usage = usage)

    @Test
    fun `the list starts loading every folder, roots only, archived hidden`() {
        with(SessionListState()) {
            assertNull(directory)
            assertTrue(loading)
            assertFalse(refreshing)
            assertTrue(rows.isEmpty())
            assertEquals("", query)
            assertFalse(showArchived)
            assertFalse(loadingMore)
            assertFalse(endReached)
            assertNull(error)
            assertNull(loadMoreError)
            assertFalse(isEmpty)
        }
        assertEquals("/srv/app", SessionListState(directory = "/srv/app").directory)
    }

    @Test
    fun `the list is empty only once loaded without rows or error`() {
        assertTrue(SessionListState(loading = false).isEmpty)
        assertFalse(SessionListState(loading = false, rows = listOf(row())).isEmpty)
        assertFalse(SessionListState(loading = false, error = SessionsFailure.Unreachable).isEmpty)
        assertTrue(SessionListState(loading = false, query = "nothing").isSearching)
        assertFalse(SessionListState(loading = false, query = "  ").isSearching)
    }

    @Test
    fun `a row carries tokens and cost`() {
        val usage = TokenUsage.of(input = 1200, output = 300, cost = Cost.parse("0.042"))
        val row = row(usage = usage)

        assertEquals(usage, row.usage)
        assertEquals(Cost.parse("0.042"), row.usage?.cost)
    }

    @Test
    fun `badges are listed from most to least urgent`() {
        assertEquals(
            listOf(
                SessionBadge.WaitingForInput,
                SessionBadge.Running,
                SessionBadge.Failed,
                SessionBadge.Interrupted,
                SessionBadge.Idle,
            ),
            SessionBadge.entries,
        )
    }

    @Test
    fun `creation starts loading in the folder it was given`() {
        with(SessionCreateState()) {
            assertTrue(loading)
            assertEquals("", directory)
            assertTrue(recentFolders.isEmpty())
            assertTrue(agents.isEmpty())
            assertNull(selectedAgent)
            assertTrue(models.isEmpty())
            assertNull(selectedModel)
            assertNull(defaultModel)
            assertEquals("", title)
            assertNull(skillId)
            assertNull(folderError)
            assertNull(catalogError)
            assertFalse(creating)
            assertNull(error)
        }
        with(SessionCreateState(directory = "/srv/app", skillId = "skill-1")) {
            assertEquals("/srv/app", directory)
            assertEquals("skill-1", skillId)
        }
    }

    @Test
    fun `creation can start once loaded, with a folder, and while not creating`() {
        assertFalse(SessionCreateState(directory = "/srv/app").canCreate)
        assertTrue(SessionCreateState(loading = false, directory = "/srv/app").canCreate)
        assertFalse(SessionCreateState(loading = false, directory = " ").canCreate)
        assertFalse(SessionCreateState(loading = false, directory = "/srv/app", creating = true).canCreate)
        // A catalog that failed leaves the server's defaults, so creating still works.
        assertTrue(SessionCreateState(loading = false, directory = "/srv/app", catalogError = SessionsFailure.Unreachable).canCreate)
    }

    @Test
    fun `management starts loading the session`() {
        with(SessionManageState()) {
            assertTrue(loading)
            assertNull(currentTitle)
            assertEquals("", title)
            assertFalse(archived)
            assertNull(directory)
            assertNull(hasChildSessions)
            assertFalse(confirmingDelete)
            assertNull(busy)
            assertNull(loadError)
            assertNull(actionError)
            assertFalse(canSaveTitle)
        }
    }

    @Test
    fun `a title is saved only when it is new and not blank`() {
        val loaded = SessionManageState(loading = false, currentTitle = "Old", title = "Old")
        assertFalse(loaded.canSaveTitle)
        assertTrue(loaded.copy(title = "New").canSaveTitle)
        assertFalse(loaded.copy(title = "   ").canSaveTitle)
        assertFalse(loaded.copy(title = " Old ").canSaveTitle)
        assertFalse(loaded.copy(title = "New", busy = ManageAction.Rename).canSaveTitle)
        assertTrue(SessionManageState(loading = false, currentTitle = null, title = "First").canSaveTitle)
    }

    @Test
    fun `titles and search text do not print`() {
        assertMasked(row(title = secretTitle), secretTitle)
        assertMasked(SessionListState(rows = listOf(row(title = secretTitle)), query = secretTitle), secretTitle)
        assertMasked(SessionListIntent.QueryChanged(secretTitle), secretTitle)
        assertMasked(SessionCreateState(title = secretTitle), secretTitle)
        assertMasked(SessionCreateIntent.TitleChanged(secretTitle), secretTitle)
        assertMasked(SessionManageState(currentTitle = secretTitle, title = secretTitle), secretTitle)
        assertMasked(SessionManageIntent.TitleChanged(secretTitle), secretTitle)
    }

    @Test
    fun `masked values still compare by content`() {
        assertEquals(SessionListIntent.QueryChanged(secretTitle), SessionListIntent.QueryChanged(secretTitle))
        assertEquals(SessionCreateIntent.TitleChanged(secretTitle), SessionCreateIntent.TitleChanged(secretTitle))
        assertEquals(SessionManageIntent.TitleChanged(secretTitle), SessionManageIntent.TitleChanged(secretTitle))
        assertEquals(row(title = secretTitle), row(title = secretTitle))
    }

    @Test
    fun `only primary agents that are not hidden are offered`() {
        val build = Agent(id = "build", name = "Build", mode = AgentMode.Primary)
        val all = Agent(id = "general", name = "General", mode = AgentMode.All)
        val sub = Agent(id = "explore", name = "Explore", mode = AgentMode.Subagent)
        val hidden = Agent(id = "title", name = "Title", mode = AgentMode.Primary, hidden = true)
        val unknown = Agent(id = "odd", name = "Odd", mode = AgentMode.Unknown("other"))

        assertEquals(listOf(build, all), selectableAgents(listOf(build, all, sub, hidden, unknown)))
    }

    @Test
    fun `a model choice carries its provider`() {
        val ref = ModelRef(providerId = "anthropic", modelId = "claude")
        assertEquals(ref, SessionCreateState(selectedModel = ref).selectedModel)
    }

    @Test
    fun `api errors map to failures a screen can word`() {
        assertEquals(SessionsFailure.Unauthorized, SessionsFailure.of(ApiError.Unauthorized))
        assertEquals(SessionsFailure.Rejected, SessionsFailure.of(ApiError.Forbidden))
        assertEquals(SessionsFailure.NotFound, SessionsFailure.of(ApiError.NotFound))
        assertEquals(SessionsFailure.Conflict, SessionsFailure.of(ApiError.Conflict()))
        assertEquals(SessionsFailure.Rejected, SessionsFailure.of(ApiError.BadRequest()))
        assertEquals(SessionsFailure.ServerFailed, SessionsFailure.of(ApiError.Server(500)))
        assertEquals(SessionsFailure.Unreachable, SessionsFailure.of(ApiError.Network))
        assertEquals(SessionsFailure.Unreachable, SessionsFailure.of(ApiError.Timeout))
        assertEquals(SessionsFailure.NotConnected, SessionsFailure.of(ApiError.Closed))
        assertEquals(SessionsFailure.Unknown, SessionsFailure.of(ApiError.VersionTooOld("2.1")))
        assertEquals(SessionsFailure.Unknown, SessionsFailure.of(ApiError.MalformedResponse))
        assertEquals(SessionsFailure.Unknown, SessionsFailure.of(ApiError.Unexpected()))
    }

    @Test
    fun `a deletion names the folder to list afterwards`() {
        assertEquals("/srv/app", SessionManageEvent.Deleted(directory = "/srv/app").directory)
        assertNull(SessionManageEvent.Deleted(directory = null).directory)
    }

    @Test
    fun `a failed action names what failed`() {
        val failed = ManageActionError(ManageAction.Delete, SessionsFailure.Conflict)
        assertEquals(ManageAction.Delete, failed.action)
        assertEquals(SessionsFailure.Conflict, failed.reason)
        assertEquals(
            listOf(ManageAction.Rename, ManageAction.Archive, ManageAction.Unarchive, ManageAction.Delete),
            ManageAction.entries,
        )
    }
}
