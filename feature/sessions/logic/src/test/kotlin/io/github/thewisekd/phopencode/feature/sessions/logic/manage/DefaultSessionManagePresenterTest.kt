package io.github.thewisekd.phopencode.feature.sessions.logic.manage

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageActionError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionManagePresenterTest {
    private val sessionId = SessionId("ses_manage")

    /** Assembled at runtime: a title is user text, and must never show in a printed state. */
    private val secretTitle = "my-" + "private-" + "title"

    private val session = Session(
        id = sessionId,
        title = "Old title",
        directory = "/srv/app",
    )

    private class Env(val test: TestScope) {
        val connection = ManageConnection(test.backgroundScope)
        val events = mutableListOf<SessionManageEvent>()

        fun presenter(id: SessionId, scope: CoroutineScope = test.backgroundScope): DefaultSessionManagePresenter =
            DefaultSessionManagePresenter(
                sessionId = id,
                connection = connection.manager,
                scope = scope,
            ).also { presenter ->
                test.backgroundScope.launch { presenter.events.collect { events += it } }
            }
    }

    private suspend fun TestScope.env(
        connect: Boolean = true,
        arrange: (FakeOpencodeClient) -> Unit = { it.sessions.seed(session) },
    ): Env {
        val env = Env(this)
        env.connection.arrange = arrange
        if (connect) {
            env.connection.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        }
        return env
    }

    private suspend fun TestScope.loaded(
        arrange: (FakeOpencodeClient) -> Unit = { it.sessions.seed(session) },
    ): Pair<Env, DefaultSessionManagePresenter> {
        val env = env(arrange = arrange)
        val presenter = env.presenter(sessionId)
        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.loadError)
        return env to presenter
    }

    private fun TestScope.send(presenter: DefaultSessionManagePresenter, intent: SessionManageIntent) {
        presenter.onIntent(intent)
        runCurrent()
    }

    private fun FakeOpencodeClient.callsOf(name: String): Int = control.calls.count { it == name }

    // Loading

    @Test
    fun `the initial state holds until the scope runs, then the session is loaded`() = runTest {
        val env = env()
        val presenter = env.presenter(sessionId)

        assertEquals(SessionManageState(), presenter.state.value)
        assertTrue(
            env.connection.lastClient().control.calls.none { it.startsWith("sessions.") },
            "nothing is asked in the constructor",
        )

        runCurrent()

        assertEquals(
            SessionManageState(
                loading = false,
                currentTitle = "Old title",
                title = "Old title",
                archived = false,
                directory = "/srv/app",
                hasChildSessions = false,
            ),
            presenter.state.value,
        )
    }

    @Test
    fun `an archived session without a title loads as such`() = runTest {
        val (_, presenter) = loaded(arrange = { it.sessions.seed(session.copy(title = null, archived = true)) })

        val state = presenter.state.value
        assertNull(state.currentTitle)
        assertEquals("", state.title)
        assertTrue(state.archived)
    }

    @Test
    fun `child sessions are asked of the server, archived ones included`() = runTest {
        val (_, presenter) = loaded(arrange = {
            it.sessions.seed(session)
            it.sessions.seed(Session(id = SessionId("ses_child"), parentId = sessionId, archived = true))
        })

        assertEquals(true, presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a child of another session does not count`() = runTest {
        val (_, presenter) = loaded(arrange = {
            it.sessions.seed(session)
            it.sessions.seed(Session(id = SessionId("ses_other")))
            it.sessions.seed(Session(id = SessionId("ses_child"), parentId = SessionId("ses_other")))
        })

        assertEquals(false, presenter.state.value.hasChildSessions)
    }

    @Test
    fun `when the children cannot be asked, they are unknown and the session still loads`() = runTest {
        val env = env()
        env.connection.failChildren = ApiError.Network
        val presenter = env.presenter(sessionId)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals("Old title", state.currentTitle)
        assertNull(state.hasChildSessions)
    }

    @Test
    fun `the children are asked of this session, archived ones included, one at most`() = runTest {
        val (env, _) = loaded()

        val children = env.connection.queries.single()
        assertEquals(SessionParentFilter.ChildrenOf(sessionId), children.parent)
        assertNull(children.archived)
        assertEquals(1, children.limit)
    }

    private val withChild: (FakeOpencodeClient) -> Unit = {
        it.sessions.seed(session)
        it.sessions.seed(Session(id = SessionId("ses_child"), parentId = sessionId))
    }

    @Test
    fun `a reconnect after the session is fetched asks the children of the new connection`() = runTest {
        val env = env(arrange = withChild)
        env.connection.afterGet = { env.connection.switchTo(PROFILE) }
        val presenter = env.presenter(sessionId)
        runCurrent()

        val second = env.connection.lastClient()
        assertEquals(2, env.connection.clients.size)
        assertEquals(1, second.callsOf("sessions.list"))
        assertNull(presenter.state.value.loadError)
        assertEquals(true, presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a reconnect during the children query asks once more on the new connection`() = runTest {
        val env = env(arrange = withChild)
        env.connection.beforeChildren = { env.connection.switchTo(PROFILE) }
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertEquals(2, env.connection.clients.size)
        assertEquals(1, env.connection.lastClient().callsOf("sessions.list"))
        assertEquals(true, presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a children query closed twice leaves them unknown`() = runTest {
        val env = env(arrange = withChild)
        env.connection.beforeChildren = {
            env.connection.beforeChildren = { env.connection.switchTo(PROFILE) }
            env.connection.switchTo(PROFILE)
        }
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertEquals(3, env.connection.clients.size)
        assertEquals(0, env.connection.lastClient().callsOf("sessions.list"))
        assertNull(presenter.state.value.loadError)
        assertNull(presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a switch to another profile after the fetch leaves the children unknown and asks it nothing`() = runTest {
        val env = env(arrange = withChild)
        env.connection.afterGet = { env.connection.switchTo(OTHER_PROFILE) }
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertEquals(0, env.connection.lastClient().callsOf("sessions.list"))
        assertNull(presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a switch to another profile during the children query does not ask it again`() = runTest {
        val env = env(arrange = withChild)
        env.connection.beforeChildren = { env.connection.switchTo(OTHER_PROFILE) }
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertEquals(0, env.connection.lastClient().callsOf("sessions.list"))
        assertNull(presenter.state.value.hasChildSessions)
    }

    @Test
    fun `a session that is gone shows not found, and retry loads it once it is there`() = runTest {
        val env = env(arrange = {})
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.NotFound, presenter.state.value.loadError)

        env.connection.lastClient().sessions.seed(session)
        send(presenter, SessionManageIntent.Retry)

        assertNull(presenter.state.value.loadError)
        assertEquals("Old title", presenter.state.value.currentTitle)
    }

    @Test
    fun `a failed load is mapped, and shows loading while retrying`() = runTest {
        val env = env()
        val client = env.connection.lastClient()
        client.control.failNext(ApiError.Timeout)
        val presenter = env.presenter(sessionId)
        runCurrent()
        assertEquals(SessionsFailure.Unreachable, presenter.state.value.loadError)

        client.control.stall()
        send(presenter, SessionManageIntent.Retry)
        assertTrue(presenter.state.value.loading)
        client.control.resume()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.loadError)
    }

    @Test
    fun `without a connection the load fails with not connected`() = runTest {
        val env = env(connect = false)
        val presenter = env.presenter(sessionId)
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.loadError)
    }

    @Test
    fun `retry does nothing while the session is loaded`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.Retry)

        assertEquals(1, env.connection.lastClient().callsOf("sessions.get"))
    }

    // Renaming

    @Test
    fun `saving the title renames the session, trimmed`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()

        send(presenter, SessionManageIntent.TitleChanged("  New title  "))
        assertTrue(presenter.state.value.canSaveTitle)
        client.control.stall()
        send(presenter, SessionManageIntent.SaveTitle)
        assertEquals(ManageAction.Rename, presenter.state.value.busy)
        client.control.resume()
        runCurrent()

        assertEquals("New title", client.sessions.get(sessionId).title)
        val state = presenter.state.value
        assertNull(state.busy)
        assertEquals("New title", state.currentTitle)
        assertEquals("New title", state.title)
        assertFalse(state.canSaveTitle)
    }

    @Test
    fun `a blank or unchanged title is not sent`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.TitleChanged("   "))
        send(presenter, SessionManageIntent.SaveTitle)
        send(presenter, SessionManageIntent.TitleChanged(" Old title "))
        send(presenter, SessionManageIntent.SaveTitle)

        assertEquals(0, env.connection.lastClient().callsOf("sessions.update"))
    }

    @Test
    fun `a failed rename keeps the typed title and shows the error until dismissed`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()

        send(presenter, SessionManageIntent.TitleChanged("New title"))
        client.control.failNext(ApiError.Conflict("busy"))
        send(presenter, SessionManageIntent.SaveTitle)

        val state = presenter.state.value
        assertNull(state.busy)
        assertEquals(ManageActionError(ManageAction.Rename, SessionsFailure.Conflict), state.actionError)
        assertEquals("Old title", state.currentTitle)
        assertEquals("New title", state.title)

        send(presenter, SessionManageIntent.DismissError)
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `an action that finds the session gone shows it as not found`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()

        client.sessions.delete(sessionId)
        send(presenter, SessionManageIntent.SetArchived(true))

        assertEquals(ManageActionError(ManageAction.Archive, SessionsFailure.NotFound), presenter.state.value.actionError)
        assertEquals(SessionsFailure.NotFound, presenter.state.value.loadError)
    }

    // Archiving

    @Test
    fun `archiving and un-archiving update the session`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()

        client.control.stall()
        send(presenter, SessionManageIntent.SetArchived(true))
        assertEquals(ManageAction.Archive, presenter.state.value.busy)
        client.control.resume()
        runCurrent()
        assertTrue(client.sessions.get(sessionId).archived)
        assertTrue(presenter.state.value.archived)
        assertNull(presenter.state.value.busy)

        client.control.stall()
        send(presenter, SessionManageIntent.SetArchived(false))
        assertEquals(ManageAction.Unarchive, presenter.state.value.busy)
        client.control.resume()
        runCurrent()
        assertFalse(client.sessions.get(sessionId).archived)
        assertFalse(presenter.state.value.archived)
    }

    @Test
    fun `archiving what is already archived sends nothing`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.SetArchived(false))

        assertEquals(0, env.connection.lastClient().callsOf("sessions.update"))
    }

    @Test
    fun `a failed archive shows the error and keeps the state`() = runTest {
        val (env, presenter) = loaded()
        env.connection.lastClient().control.failNext(ApiError.Server(500, null))

        send(presenter, SessionManageIntent.SetArchived(true))

        assertFalse(presenter.state.value.archived)
        assertEquals(
            ManageActionError(ManageAction.Archive, SessionsFailure.ServerFailed),
            presenter.state.value.actionError,
        )
    }

    @Test
    fun `while an action runs the others wait`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()

        send(presenter, SessionManageIntent.TitleChanged("New title"))
        client.control.stall()
        send(presenter, SessionManageIntent.SaveTitle)
        send(presenter, SessionManageIntent.SetArchived(true))
        send(presenter, SessionManageIntent.SaveTitle)
        send(presenter, SessionManageIntent.RequestDelete)
        client.control.resume()
        runCurrent()

        assertEquals(1, client.callsOf("sessions.update"))
        assertFalse(client.sessions.get(sessionId).archived)
        assertFalse(presenter.state.value.confirmingDelete)
    }

    @Test
    fun `nothing is sent before the session has loaded`() = runTest {
        val env = env()
        val presenter = env.presenter(sessionId)

        presenter.onIntent(SessionManageIntent.SetArchived(true))
        presenter.onIntent(SessionManageIntent.RequestDelete)
        presenter.onIntent(SessionManageIntent.ConfirmDelete)
        runCurrent()

        val client = env.connection.lastClient()
        assertEquals(0, client.callsOf("sessions.update"))
        assertEquals(0, client.callsOf("sessions.delete"))
    }

    // Deleting

    @Test
    fun `asking to delete only shows the confirmation, and cancel hides it`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.RequestDelete)
        assertTrue(presenter.state.value.confirmingDelete)
        send(presenter, SessionManageIntent.CancelDelete)

        assertFalse(presenter.state.value.confirmingDelete)
        assertEquals(0, env.connection.lastClient().callsOf("sessions.delete"))
        assertTrue(env.events.isEmpty())
    }

    @Test
    fun `confirming without asking first deletes nothing`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.ConfirmDelete)

        assertEquals(0, env.connection.lastClient().callsOf("sessions.delete"))
    }

    @Test
    fun `a confirmed delete is sent once, with its children, and reports the folder`() = runTest {
        val (env, presenter) = loaded(arrange = {
            it.sessions.seed(session)
            it.sessions.seed(Session(id = SessionId("ses_child"), parentId = sessionId))
        })
        val client = env.connection.lastClient()
        assertEquals(true, presenter.state.value.hasChildSessions)

        send(presenter, SessionManageIntent.RequestDelete)
        client.control.stall()
        presenter.onIntent(SessionManageIntent.ConfirmDelete)
        presenter.onIntent(SessionManageIntent.ConfirmDelete)
        runCurrent()
        assertEquals(ManageAction.Delete, presenter.state.value.busy)
        assertFalse(presenter.state.value.confirmingDelete)
        client.control.resume()
        runCurrent()
        send(presenter, SessionManageIntent.RequestDelete)
        send(presenter, SessionManageIntent.ConfirmDelete)

        assertEquals(1, client.callsOf("sessions.delete"))
        assertTrue(client.sessions.list(SessionQuery(parent = SessionParentFilter.Any)).items.isEmpty())
        assertEquals(listOf<SessionManageEvent>(SessionManageEvent.Deleted("/srv/app")), env.events)
    }

    @Test
    fun `a session without a folder reports a null folder`() = runTest {
        val (env, presenter) = loaded(arrange = { it.sessions.seed(session.copy(directory = null)) })

        send(presenter, SessionManageIntent.RequestDelete)
        send(presenter, SessionManageIntent.ConfirmDelete)

        assertEquals(listOf<SessionManageEvent>(SessionManageEvent.Deleted(null)), env.events)
    }

    @Test
    fun `a failed delete shows the error and reports nothing`() = runTest {
        val (env, presenter) = loaded()
        val client = env.connection.lastClient()
        client.control.failNext(ApiError.Forbidden)

        send(presenter, SessionManageIntent.RequestDelete)
        send(presenter, SessionManageIntent.ConfirmDelete)

        assertEquals(
            ManageActionError(ManageAction.Delete, SessionsFailure.Rejected),
            presenter.state.value.actionError,
        )
        assertNull(presenter.state.value.busy)
        assertTrue(env.events.isEmpty())
        assertEquals("Old title", client.sessions.get(sessionId).title)
    }

    // Which server

    @Test
    fun `after a switch to another profile nothing is sent to the new server`() = runTest {
        val (env, presenter) = loaded()
        val first = env.connection.lastClient()
        send(presenter, SessionManageIntent.RequestDelete)

        env.connection.connect(OTHER_PROFILE)
        runCurrent()
        val second = env.connection.lastClient()
        assertNotSame(first, second)
        assertIs<ConnectionState.Connected>(env.connection.manager.state.value)

        send(presenter, SessionManageIntent.ConfirmDelete)
        send(presenter, SessionManageIntent.TitleChanged("New title"))
        send(presenter, SessionManageIntent.SaveTitle)
        send(presenter, SessionManageIntent.SetArchived(true))

        assertEquals(0, second.callsOf("sessions.delete"))
        assertEquals(0, second.callsOf("sessions.update"))
        assertTrue(second.sessions.list().items.isNotEmpty())
        assertEquals(
            ManageActionError(ManageAction.Archive, SessionsFailure.NotConnected),
            presenter.state.value.actionError,
        )
        assertTrue(env.events.isEmpty())
    }

    @Test
    fun `a retry after a switch to another profile does not load from the new server`() = runTest {
        val env = env()
        val client = env.connection.lastClient()
        client.control.failNext(ApiError.Network)
        val presenter = env.presenter(sessionId)
        runCurrent()
        assertEquals(SessionsFailure.Unreachable, presenter.state.value.loadError)

        env.connection.connect(OTHER_PROFILE)
        runCurrent()
        val second = env.connection.lastClient()
        send(presenter, SessionManageIntent.Retry)

        assertEquals(0, second.callsOf("sessions.get"))
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.loadError)
    }

    @Test
    fun `after a reconnect to the same profile actions go to the new connection`() = runTest {
        val (env, presenter) = loaded()
        val first = env.connection.lastClient()

        env.connection.connect(PROFILE)
        runCurrent()
        val second = env.connection.lastClient()
        assertNotSame(first, second)

        send(presenter, SessionManageIntent.SetArchived(true))

        assertNull(presenter.state.value.actionError)
        assertTrue(second.sessions.get(sessionId).archived)
    }

    @Test
    fun `without a connection an action fails with not connected`() = runTest {
        val (env, presenter) = loaded()

        env.connection.manager.disconnect()
        runCurrent()
        send(presenter, SessionManageIntent.RequestDelete)
        send(presenter, SessionManageIntent.ConfirmDelete)

        assertEquals(
            ManageActionError(ManageAction.Delete, SessionsFailure.NotConnected),
            presenter.state.value.actionError,
        )
        assertTrue(env.events.isEmpty())
    }

    // The rest

    @Test
    fun `close asks to leave`() = runTest {
        val (env, presenter) = loaded()

        send(presenter, SessionManageIntent.Close)

        assertEquals(listOf<SessionManageEvent>(SessionManageEvent.Close), env.events)
    }

    @Test
    fun `a printed state shows no title`() = runTest {
        val (_, presenter) = loaded(arrange = { it.sessions.seed(session.copy(title = secretTitle)) })

        send(presenter, SessionManageIntent.TitleChanged(secretTitle + "-typed"))

        assertFalse(presenter.state.value.toString().contains(secretTitle))
    }
}
