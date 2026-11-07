package io.github.thewisekd.phopencode.feature.sessions.logic.list

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionRow
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionListPresenterTest {
    private class Env(val test: TestScope) {
        val connection = SessionsConnection(test.backgroundScope)
        val events = mutableListOf<SessionListEvent>()
        val states = mutableListOf<SessionListState>()

        val server: FakeOpencodeClient
            get() = connection.server

        fun presenter(
            directory: String? = null,
            pageSize: Int = DefaultSessionListPresenter.DEFAULT_PAGE_SIZE,
        ): DefaultSessionListPresenter = DefaultSessionListPresenter(
            directory = directory,
            connection = connection.manager,
            scope = test.backgroundScope,
            pageSize = pageSize,
        ).also { presenter ->
            test.backgroundScope.launch { presenter.events.collect { events += it } }
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                presenter.state.collect { states += it }
            }
        }

        fun calls(name: String): Int = server.control.calls.count { it == name }
    }

    private suspend fun TestScope.env(connect: Boolean = true): Env {
        val env = Env(this)
        if (connect) {
            env.connection.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        }
        return env
    }

    private var clock = 1_000L

    /** A session newer than every one made before it. */
    private fun session(
        id: String,
        title: String? = "Title $id",
        directory: String? = "/srv/app",
        parent: String? = null,
        archived: Boolean = false,
        outcome: SessionOutcome? = null,
        usage: TokenUsage? = null,
    ) = Session(
        id = SessionId(id),
        parentId = parent?.let(::SessionId),
        title = title,
        directory = directory,
        updatedAt = Instant.fromEpochSeconds(clock++),
        archived = archived,
        outcome = outcome,
        usage = usage,
    )

    /** Seeded oldest first: the fake lists newest first. */
    private fun Env.seed(vararg sessions: Session) = sessions.forEach(server.sessions::seed)

    private val DefaultSessionListPresenter.ids: List<String>
        get() = state.value.rows.map { it.id.value }

    private fun DefaultSessionListPresenter.row(id: String): SessionRow = state.value.rows.single { it.id.value == id }

    private fun TestScope.send(presenter: DefaultSessionListPresenter, intent: SessionListIntent) {
        presenter.onIntent(intent)
        runCurrent()
    }

    // Loading

    @Test
    fun `nothing loads in the constructor, then the root sessions load newest first`() = runTest {
        val env = env()
        val usage = TokenUsage.of(input = 120, output = 30)
        env.seed(
            session("a", usage = usage),
            session("a-child", parent = "a"),
            session("b", directory = "/srv/other"),
        )

        val presenter = env.presenter()

        assertEquals(SessionListState(), presenter.state.value)
        assertEquals(0, env.calls("sessions.list"))
        runCurrent()
        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("b", "a"), presenter.ids)
        val row = presenter.row("a")
        assertEquals("Title a", row.title)
        assertEquals("/srv/app", row.directory)
        assertEquals(usage, row.usage)
        assertEquals(SessionBadge.Idle, row.status)
        assertFalse(row.archived)
    }

    @Test
    fun `a folder lists only its own sessions`() = runTest {
        val env = env()
        env.seed(session("a"), session("b", directory = "/srv/other"), session("c"))

        val presenter = env.presenter(directory = "/srv/app")
        runCurrent()

        assertEquals(listOf("c", "a"), presenter.ids)
        assertEquals("/srv/app", presenter.state.value.directory)
    }

    @Test
    fun `an empty server shows the empty state`() = runTest {
        val env = env()

        val presenter = env.presenter()
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
        assertTrue(presenter.state.value.endReached)
    }

    @Test
    fun `without a connection the list shows not connected, and loads once one stands`() = runTest {
        val env = env(connect = false)
        env.seed(session("a"))

        val presenter = env.presenter()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.error)

        env.connection.connect()
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf("a"), presenter.ids)
    }

    @Test
    fun `a failed first page shows its error and retry loads it`() = runTest {
        val env = env()
        env.seed(session("a"))
        env.server.control.failNext(ApiError.Network)

        val presenter = env.presenter()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.Unreachable, presenter.state.value.error)
        assertTrue(presenter.state.value.rows.isEmpty())

        send(presenter, SessionListIntent.Retry)

        assertNull(presenter.state.value.error)
        assertEquals(listOf("a"), presenter.ids)
    }

    @Test
    fun `an event stream that cannot open shows its error, and retry opens it again`() = runTest {
        val env = env()
        env.seed(session("a"))
        env.server.events.refuseConnections(ApiError.Unauthorized)

        val presenter = env.presenter()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.Unauthorized, presenter.state.value.error)

        env.server.events.refuseConnections(null)
        send(presenter, SessionListIntent.Retry)

        assertNull(presenter.state.value.error)
        assertEquals(listOf("a"), presenter.ids)
        assertEquals(2, env.server.events.activeConnections.value)
    }

    @Test
    fun `dismissing the error clears it`() = runTest {
        val env = env()
        env.server.control.failNext(ApiError.Server(500))
        val presenter = env.presenter()
        runCurrent()
        assertEquals(SessionsFailure.ServerFailed, presenter.state.value.error)

        send(presenter, SessionListIntent.DismissError)

        assertNull(presenter.state.value.error)
    }

    // Pagination

    @Test
    fun `pages load one after another until an empty page ends the list`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"), session("d"), session("e"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()

        assertEquals(listOf("e", "d"), presenter.ids)
        assertFalse(presenter.state.value.endReached)

        send(presenter, SessionListIntent.LoadMore)
        assertEquals(listOf("e", "d", "c", "b"), presenter.ids)

        send(presenter, SessionListIntent.LoadMore)
        assertEquals(listOf("e", "d", "c", "b", "a"), presenter.ids)

        send(presenter, SessionListIntent.LoadMore)
        assertTrue(presenter.state.value.endReached)
        assertFalse(presenter.state.value.loadingMore)
        val calls = env.calls("sessions.list")

        send(presenter, SessionListIntent.LoadMore)
        assertEquals(calls, env.calls("sessions.list"), "nothing loads at the end")
    }

    @Test
    fun `the next page shows loading while it loads`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        env.server.control.stall()

        send(presenter, SessionListIntent.LoadMore)
        assertTrue(presenter.state.value.loadingMore)
        send(presenter, SessionListIntent.LoadMore)

        env.server.control.resume()
        runCurrent()
        assertFalse(presenter.state.value.loadingMore)
        assertEquals(listOf("c", "b", "a"), presenter.ids)
    }

    @Test
    fun `a failed next page keeps the rows, and asking again retries it`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        env.server.control.failNext(ApiError.Timeout)

        send(presenter, SessionListIntent.LoadMore)

        assertEquals(SessionsFailure.Unreachable, presenter.state.value.loadMoreError)
        assertNull(presenter.state.value.error)
        assertEquals(listOf("c", "b"), presenter.ids)

        send(presenter, SessionListIntent.Retry)

        assertNull(presenter.state.value.loadMoreError)
        assertEquals(listOf("c", "b", "a"), presenter.ids)
    }

    @Test
    fun `a session already shown is not shown twice when a later page repeats it`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        // A newer session shifts the server's pages by one, so the next page starts with "b" again.
        env.seed(session("d"))

        send(presenter, SessionListIntent.LoadMore)

        assertEquals(listOf("c", "b", "a"), presenter.ids)
    }

    @Test
    fun `a stream failure while the next page loads stops showing it as loading`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        env.server.control.stall()
        send(presenter, SessionListIntent.LoadMore)
        assertTrue(presenter.state.value.loadingMore)

        env.connection.breaker.fail(ApiError.Unauthorized)
        runCurrent()

        assertIs<ConnectionState.Connected>(env.connection.manager.state.value, "only the list's stream failed")
        assertEquals(SessionsFailure.Unauthorized, presenter.state.value.error)
        assertFalse(presenter.state.value.loadingMore)

        env.server.control.resume()
        runCurrent()
        assertFalse(presenter.state.value.loadingMore)
        assertEquals(listOf("c", "b"), presenter.ids)
    }

    @Test
    fun `a next page that left before a delete or an archive does not bring those sessions back`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"), session("d"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        env.server.control.stall()
        send(presenter, SessionListIntent.LoadMore)

        // The page was read on the server before these: it still holds both sessions.
        env.server.events.emit(ServerEvent.SessionDeleted(SessionId("b")))
        env.server.events.emit(ServerEvent.SessionArchived(SessionId("a")))
        runCurrent()
        env.server.control.resume()
        runCurrent()

        assertEquals(listOf("d", "c"), presenter.ids)
        assertFalse(presenter.state.value.loadingMore)
    }

    @Test
    fun `a new session's lookup that ends after its delete does not bring it back`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        env.server.control.stall()
        env.seed(session("x"))

        env.server.events.emit(ServerEvent.SessionCreated(SessionId("x")))
        runCurrent()
        env.server.events.emit(ServerEvent.SessionDeleted(SessionId("x")))
        runCurrent()
        env.server.control.resume()
        runCurrent()

        assertEquals(listOf("a"), presenter.ids)
    }

    @Test
    fun `a session renamed out of the search before its page arrives stays out`() = runTest {
        val env = env()
        env.seed(session("a", title = "Login a"), session("b", title = "Login b"), session("c", title = "Login c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        send(presenter, SessionListIntent.QueryChanged("login"))
        advanceTimeBy(DefaultSessionListPresenter.SEARCH_DEBOUNCE + 1.milliseconds)
        runCurrent()
        env.server.control.stall()
        send(presenter, SessionListIntent.LoadMore)

        // The page was read with the old title.
        env.server.events.emit(ServerEvent.SessionRenamed(SessionId("a"), "Other"))
        runCurrent()
        env.server.control.resume()
        runCurrent()

        assertEquals(listOf("c", "b"), presenter.ids)
    }

    @Test
    fun `usage and an ending that arrive during a new session's lookup show on its row`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        env.server.control.stall()
        env.seed(session("x"))
        val usage = TokenUsage.of(input = 40, output = 2)

        env.server.events.emit(ServerEvent.SessionCreated(SessionId("x")))
        runCurrent()
        env.server.events.emit(ServerEvent.UsageUpdated(SessionId("x"), usage))
        env.server.events.emit(ServerEvent.ExecutionStarted(SessionId("x")))
        env.server.events.emit(ServerEvent.ExecutionFailed(SessionId("x")))
        runCurrent()
        env.server.control.resume()
        runCurrent()

        assertEquals(listOf("x", "a"), presenter.ids)
        assertEquals(usage, presenter.row("x").usage)
        assertEquals(SessionBadge.Failed, presenter.row("x").status)
    }

    @Test
    fun `a session unarchived into a folder not listed yet shows that folder's pending items`() = runTest {
        val env = env()
        env.seed(session("a", directory = "/srv/one"), session("b", directory = "/srv/two", archived = true))
        env.server.interactions.seedForm(Form(FormId("f1"), SessionId("b"), FormKind.Question))
        val presenter = env.presenter()
        runCurrent()
        assertEquals(listOf("a"), presenter.ids)

        env.server.sessions.update(SessionId("b"), SessionUpdate(archived = false))
        runCurrent()

        assertEquals(listOf("b", "a"), presenter.ids)
        assertEquals(SessionBadge.WaitingForInput, presenter.row("b").status)
    }

    @Test
    fun `an unarchived session comes back even after it was archived`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter()
        runCurrent()

        env.server.sessions.update(SessionId("b"), SessionUpdate(archived = true))
        env.server.sessions.update(SessionId("b"), SessionUpdate(archived = false))
        runCurrent()

        assertEquals(listOf("c", "b", "a"), presenter.ids)
    }

    // Refresh

    @Test
    fun `pull to refresh reloads the first page while the rows stay`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        env.server.control.stall()

        send(presenter, SessionListIntent.Refresh)
        assertTrue(presenter.state.value.refreshing)
        assertFalse(presenter.state.value.loading)
        assertEquals(listOf("a"), presenter.ids)

        env.seed(session("b"))
        env.server.control.resume()
        runCurrent()

        assertFalse(presenter.state.value.refreshing)
        assertEquals(listOf("b", "a"), presenter.ids)
    }

    @Test
    fun `a failed refresh shows the error over the rows`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        env.server.control.failNext(ApiError.Network)

        send(presenter, SessionListIntent.Refresh)

        assertFalse(presenter.state.value.refreshing)
        assertEquals(SessionsFailure.Unreachable, presenter.state.value.error)
        assertEquals(listOf("a"), presenter.ids)
    }

    // Search and archive

    @Test
    fun `search waits for typing to pause, then lists the matches`() = runTest {
        val env = env()
        env.seed(session("a", title = "Fix login"), session("b", title = "Add search"), session("c", title = "Login page"))
        val presenter = env.presenter()
        runCurrent()
        val calls = env.calls("sessions.list")

        send(presenter, SessionListIntent.QueryChanged("lo"))
        send(presenter, SessionListIntent.QueryChanged("log"))
        assertEquals("log", presenter.state.value.query)
        assertTrue(presenter.state.value.isSearching)
        assertEquals(calls, env.calls("sessions.list"), "nothing is asked while typing")

        advanceTimeBy(DefaultSessionListPresenter.SEARCH_DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertEquals(calls + 1, env.calls("sessions.list"))
        assertEquals(listOf("c", "a"), presenter.ids)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a search with no match is not the empty server`() = runTest {
        val env = env()
        env.seed(session("a", title = "Fix login"))
        val presenter = env.presenter()
        runCurrent()

        send(presenter, SessionListIntent.QueryChanged("zzz"))
        advanceTimeBy(DefaultSessionListPresenter.SEARCH_DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
        assertTrue(presenter.state.value.isSearching)

        send(presenter, SessionListIntent.QueryChanged(""))
        advanceTimeBy(DefaultSessionListPresenter.SEARCH_DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertEquals(listOf("a"), presenter.ids)
    }

    @Test
    fun `a session renamed out of the search leaves the results, and a later page does not bring it back`() = runTest {
        val env = env()
        env.seed(session("a", title = "Login a"), session("b", title = "Login b"), session("c", title = "Login c"))
        val presenter = env.presenter(pageSize = 2)
        runCurrent()
        send(presenter, SessionListIntent.QueryChanged("login"))
        advanceTimeBy(DefaultSessionListPresenter.SEARCH_DEBOUNCE + 1.milliseconds)
        runCurrent()
        assertEquals(listOf("c", "b"), presenter.ids)

        env.server.events.emit(ServerEvent.SessionRenamed(SessionId("c"), "Other"))
        env.server.events.emit(ServerEvent.SessionRenamed(SessionId("b"), "Login again"))
        runCurrent()
        assertEquals(listOf("b"), presenter.ids)
        assertEquals("Login again", presenter.row("b").title)

        // The server still has the old title: its next page repeats nothing and adds "a".
        send(presenter, SessionListIntent.LoadMore)
        assertEquals(listOf("b", "a"), presenter.ids)
    }

    @Test
    fun `archived sessions show only when asked for`() = runTest {
        val env = env()
        env.seed(session("a"), session("b", archived = true))
        val presenter = env.presenter()
        runCurrent()

        assertEquals(listOf("a"), presenter.ids)

        send(presenter, SessionListIntent.ShowArchived(true))

        assertTrue(presenter.state.value.showArchived)
        assertEquals(listOf("b", "a"), presenter.ids)
        assertTrue(presenter.row("b").archived)

        send(presenter, SessionListIntent.ShowArchived(false))

        assertEquals(listOf("a"), presenter.ids)
    }

    // Badges

    @Test
    fun `the badge shows running, failed, interrupted and idle`() = runTest {
        val env = env()
        env.seed(
            session("idle", outcome = SessionOutcome.Succeeded),
            session("failed", outcome = SessionOutcome.Failed),
            session("interrupted", outcome = SessionOutcome.Interrupted),
            session("running", outcome = SessionOutcome.Failed),
        )
        env.server.sessions.setActivity(SessionId("running"), SessionActivity.Running)

        val presenter = env.presenter()
        runCurrent()

        assertEquals(SessionBadge.Idle, presenter.row("idle").status)
        assertEquals(SessionBadge.Failed, presenter.row("failed").status)
        assertEquals(SessionBadge.Interrupted, presenter.row("interrupted").status)
        assertEquals(SessionBadge.Running, presenter.row("running").status)
    }

    @Test
    fun `a pending form or permission, also of a child session, shows waiting for input over running`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("b-child", parent = "b"), session("c"))
        env.server.sessions.setActivity(SessionId("a"), SessionActivity.Running)
        env.server.interactions.seedForm(Form(FormId("f1"), SessionId("a"), FormKind.Question))
        env.server.interactions.seedPermission(PermissionRequest(PermissionRequestId("p1"), SessionId("b-child"), "bash"))

        val presenter = env.presenter()
        runCurrent()

        assertEquals(SessionBadge.WaitingForInput, presenter.row("a").status)
        assertEquals(SessionBadge.WaitingForInput, presenter.row("b").status)
        assertEquals(SessionBadge.Idle, presenter.row("c").status)
    }

    @Test
    fun `pending items of every listed folder count when no folder is set`() = runTest {
        val env = env()
        env.seed(session("a", directory = "/srv/one"), session("b", directory = "/srv/two"))
        env.server.interactions.seedForm(Form(FormId("f1"), SessionId("a"), FormKind.Question))
        env.server.interactions.seedPermission(PermissionRequest(PermissionRequestId("p1"), SessionId("b"), "bash"))

        val presenter = env.presenter()
        runCurrent()

        assertEquals(SessionBadge.WaitingForInput, presenter.row("a").status)
        assertEquals(SessionBadge.WaitingForInput, presenter.row("b").status)
    }

    // Live updates

    @Test
    fun `a new root session appears at the top, a new child session does not`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        assertEquals(2, env.server.events.activeConnections.value)

        val created = env.server.sessions.create(CreateSessionRequest(title = "New", location = Location("/srv/app")))
        env.server.sessions.create(CreateSessionRequest(parentId = created.id, title = "Child"))
        runCurrent()

        assertEquals(listOf(created.id.value, "a"), presenter.ids)
        assertEquals("New", presenter.row(created.id.value).title)
    }

    @Test
    fun `a new session of another folder stays out of a folder's list`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter(directory = "/srv/app")
        runCurrent()

        env.server.sessions.create(CreateSessionRequest(title = "Elsewhere", location = Location("/srv/other")))
        runCurrent()

        assertEquals(listOf("a"), presenter.ids)
    }

    @Test
    fun `renames, deletes and usage updates show at once`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"))
        val presenter = env.presenter()
        runCurrent()
        val usage = TokenUsage.of(input = 500, output = 70)

        env.server.sessions.update(SessionId("a"), SessionUpdate(title = "Renamed"))
        env.server.sessions.delete(SessionId("b"))
        env.server.events.emit(ServerEvent.UsageUpdated(SessionId("a"), usage))
        runCurrent()

        assertEquals(listOf("a"), presenter.ids)
        assertEquals("Renamed", presenter.row("a").title)
        assertEquals(usage, presenter.row("a").usage)
    }

    @Test
    fun `archiving hides a row and unarchiving brings it back in its place`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"), session("c"))
        val presenter = env.presenter()
        runCurrent()

        env.server.sessions.update(SessionId("b"), SessionUpdate(archived = true))
        runCurrent()
        assertEquals(listOf("c", "a"), presenter.ids)

        env.server.sessions.update(SessionId("b"), SessionUpdate(archived = false))
        runCurrent()
        assertEquals(listOf("c", "b", "a"), presenter.ids)
    }

    @Test
    fun `archiving marks the row when archived sessions are shown`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        send(presenter, SessionListIntent.ShowArchived(true))

        env.server.sessions.update(SessionId("a"), SessionUpdate(archived = true))
        runCurrent()

        assertTrue(presenter.row("a").archived)
    }

    @Test
    fun `execution events move the badge`() = runTest {
        val env = env()
        env.seed(session("a"), session("b"))
        val presenter = env.presenter()
        runCurrent()
        val events = env.server.events

        events.emit(ServerEvent.ExecutionStarted(SessionId("a")))
        events.emit(ServerEvent.ExecutionStarted(SessionId("b")))
        runCurrent()
        assertEquals(SessionBadge.Running, presenter.row("a").status)
        assertEquals(SessionBadge.Running, presenter.row("b").status)

        events.emit(ServerEvent.ExecutionFailed(SessionId("a")))
        events.emit(ServerEvent.ExecutionInterrupted(SessionId("b")))
        runCurrent()
        assertEquals(SessionBadge.Failed, presenter.row("a").status)
        assertEquals(SessionBadge.Interrupted, presenter.row("b").status)

        events.emit(ServerEvent.ExecutionStarted(SessionId("a")))
        events.emit(ServerEvent.ExecutionSucceeded(SessionId("a")))
        runCurrent()
        assertEquals(SessionBadge.Idle, presenter.row("a").status)
    }

    @Test
    fun `forms and permissions of a child session wait on its root, and the child is looked up once`() = runTest {
        val env = env()
        env.seed(session("a"), session("a-child", parent = "a"))
        val presenter = env.presenter()
        runCurrent()
        val gets = env.calls("sessions.get")

        val form = Form(FormId("f1"), SessionId("a-child"), FormKind.Question)
        val request = PermissionRequest(PermissionRequestId("p1"), SessionId("a-child"), "bash")
        env.server.interactions.seedPermission(request)
        env.server.events.emit(ServerEvent.FormCreated(form))
        env.server.events.emit(ServerEvent.PermissionAsked(request))
        runCurrent()

        assertEquals(SessionBadge.WaitingForInput, presenter.row("a").status)
        assertEquals(gets + 1, env.calls("sessions.get"))

        env.server.events.emit(ServerEvent.FormReplied(SessionId("a-child"), FormId("f1")))
        runCurrent()
        assertEquals(SessionBadge.WaitingForInput, presenter.row("a").status, "the permission still waits")

        env.server.interactions.replyPermission(SessionId("a-child"), PermissionRequestId("p1"), PermissionDecision.Once)
        runCurrent()
        assertEquals(SessionBadge.Idle, presenter.row("a").status)
    }

    @Test
    fun `a child created live is known as its parent's without a lookup`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        val child = env.server.sessions.create(CreateSessionRequest(parentId = SessionId("a")))
        runCurrent()
        val gets = env.calls("sessions.get")

        env.server.events.emit(ServerEvent.FormCreated(Form(FormId("f1"), child.id, FormKind.Question)))
        runCurrent()

        assertEquals(SessionBadge.WaitingForInput, presenter.row("a").status)
        assertEquals(gets, env.calls("sessions.get"))
    }

    @Test
    fun `each reconnect of the event stream reloads the first page`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()

        // Missed while the stream was away: the server replays nothing.
        env.seed(session("b"))
        env.server.events.emit(ServerEvent.Connected())
        runCurrent()

        assertEquals(listOf("b", "a"), presenter.ids)
    }

    @Test
    fun `a new connection loads from its own client`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()

        env.connection.server = FakeOpencodeClient().apply {
            sessions.seed(session("a"))
            sessions.seed(session("b"))
        }
        env.connection.manager.reconnect()
        runCurrent()
        // The manager waits its first reconnect delay before connecting again.
        advanceTimeBy(2.seconds)
        runCurrent()

        assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        assertEquals(listOf("b", "a"), presenter.ids)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a connection to another profile clears the rows before it loads`() = runTest {
        val env = env()
        env.seed(session("a"))
        val presenter = env.presenter()
        runCurrent()
        assertEquals(listOf("a"), presenter.ids)

        env.connection.server = FakeOpencodeClient().apply { sessions.seed(session("other")) }
        val before = env.states.size
        env.connection.connect(OTHER_PROFILE)
        runCurrent()

        assertEquals(listOf("other"), presenter.ids)
        val after = env.states.drop(before)
        assertTrue(after.any { it.loading && it.rows.isEmpty() }, "the old server's rows are cleared first")
        assertTrue(after.none { state -> state.rows.any { it.id.value == "a" } && state.rows.any { it.id.value == "other" } })
    }

    // Navigation

    @Test
    fun `open, manage and create are reported as events`() = runTest {
        val env = env()
        val presenter = env.presenter(directory = "/srv/app")
        runCurrent()

        send(presenter, SessionListIntent.Open(SessionId("a")))
        send(presenter, SessionListIntent.Manage(SessionId("b")))
        send(presenter, SessionListIntent.Create)

        assertEquals(
            listOf(
                SessionListEvent.OpenChat(SessionId("a")),
                SessionListEvent.OpenManage(SessionId("b")),
                SessionListEvent.OpenCreate("/srv/app"),
            ),
            env.events,
        )
    }

    @Test
    fun `files and git open the list's folder`() = runTest {
        val env = env()
        val presenter = env.presenter(directory = "/srv/app")
        runCurrent()
        assertTrue(presenter.state.value.hasFolder)

        send(presenter, SessionListIntent.OpenFiles)
        send(presenter, SessionListIntent.OpenGit)

        assertEquals(
            listOf(SessionListEvent.OpenFiles("/srv/app"), SessionListEvent.OpenGit("/srv/app")),
            env.events,
        )
    }

    @Test
    fun `files and git do nothing for the list of every folder`() = runTest {
        val env = env()
        val presenter = env.presenter(directory = null)
        runCurrent()
        assertFalse(presenter.state.value.hasFolder)

        send(presenter, SessionListIntent.OpenFiles)
        send(presenter, SessionListIntent.OpenGit)

        assertEquals(emptyList(), env.events)
    }

    @Test
    fun `a blank folder has no files or git`() = runTest {
        val env = env()
        val presenter = env.presenter(directory = " ")
        runCurrent()
        assertFalse(presenter.state.value.hasFolder)

        send(presenter, SessionListIntent.OpenFiles)
        send(presenter, SessionListIntent.OpenGit)

        assertEquals(emptyList(), env.events)
    }
}
