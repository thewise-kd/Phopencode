package io.github.thewisekd.phopencode.feature.notify.logic.pending

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingItem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DefaultPendingPresenter] combines the forms and permissions of every session of the active
 * connection, follows its event stream, and starts over on each new generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPendingPresenterTest {
    /** Server text that must never reach the state's printed form or a failure; assembled at runtime. */
    private val sentinel = "hunter" + "2"
    private val secret = "token" + "=" + sentinel + " /home/user/.env"

    private val alpha = Session(id = SessionId("ses_alpha"), title = "Alpha")
    private val alphaChild = Session(id = SessionId("ses_alpha_child"), parentId = alpha.id, title = "Subagent")
    private val beta = Session(id = SessionId("ses_beta"), title = "Beta", directory = "/work/beta")

    private fun form(id: String, session: SessionId) =
        Form(id = FormId(id), sessionId = session, kind = FormKind.Question, title = "$secret?")

    private fun permission(id: String, session: SessionId) =
        PermissionRequest(id = PermissionRequestId(id), sessionId = session, action = "bash", resources = listOf(secret))

    /** A server with sessions in the default folder and in another one, and items in both. */
    private fun server(): FakeOpencodeClient = FakeOpencodeClient().apply {
        sessions.seed(alpha)
        sessions.seed(alphaChild)
        sessions.seed(beta)
        interactions.seedForm(form("frm_a", alpha.id))
        interactions.seedPermission(permission("per_a_child", alphaChild.id))
        interactions.seedForm(form("frm_b", beta.id))
        interactions.seedPermission(permission("per_b", beta.id))
    }

    /**
     * A connection whose client and generation can change or go away (null). Like the app's
     * connection, a call takes the client standing when it starts; switching closes the old one.
     */
    private class FakeConnection(client: OpencodeClient?, generation: Long = 1) : NotifyConnection {
        private val current = MutableStateFlow(client?.let { it to generation })

        var calls = 0

        override val generations: Flow<Long?> = current.map { it?.second }.distinctUntilChanged()

        fun switch(client: OpencodeClient?, generation: Long) {
            val old = current.value?.first
            current.value = client?.let { it to generation }
            if (old !== client) old?.close()
        }

        override suspend fun <T> call(block: suspend (client: OpencodeClient, generation: Long) -> T): T {
            calls++
            val (client, generation) = current.value ?: throw ApiException(ApiError.Closed)
            return block(client, generation)
        }
    }

    /**
     * [client] whose forms and permissions cannot be listed for the folders in [broken]; null there
     * is the server's default folder.
     */
    private class BrokenFolders(private val client: FakeOpencodeClient) : OpencodeClient by client {
        val broken = mutableSetOf<String?>()

        override val interactions: InteractionApi = object : InteractionApi by client.interactions {
            override suspend fun listForms(sessionId: SessionId?, location: Location?): List<Form> {
                check(location)
                return client.interactions.listForms(sessionId, location)
            }

            override suspend fun listPermissions(sessionId: SessionId?, location: Location?): List<PermissionRequest> {
                check(location)
                return client.interactions.listPermissions(sessionId, location)
            }
        }

        private fun check(location: Location?) {
            if (location?.directory in broken) throw ApiException(ApiError.Server(500, "stale folder"))
        }
    }

    /**
     * [client] whose next `sessions.get` after [gate] is set answers with what the server held at the
     * call, but only once [gate] completes: an answer that arrives late.
     */
    private class LateLookups(private val client: FakeOpencodeClient) : OpencodeClient by client {
        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override val sessions: SessionApi = object : SessionApi by client.sessions {
            override suspend fun get(id: SessionId): Session {
                val answer = client.sessions.get(id)
                gate?.let {
                    gate = null
                    it.await()
                }
                return answer
            }
        }
    }

    private fun TestScope.presenter(connection: NotifyConnection, scope: CoroutineScope = backgroundScope) =
        DefaultPendingPresenter(connection = connection, scope = scope).also { runCurrent() }

    private fun PendingState.keys(): List<String> = items.map { it.key }

    @Test
    fun `forms and permissions of every session and folder are combined`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(setOf("form:frm_a", "permission:per_a_child", "form:frm_b", "permission:per_b"), state.keys().toSet())
        assertEquals(4, state.items.size)

        val child = state.items.single { it.key == "permission:per_a_child" }
        assertEquals(alphaChild.id, child.sessionId)
        assertEquals(alpha.id, child.parentId)
        assertEquals("Subagent", child.sessionTitle)
        assertTrue(child.isChild)

        val root = state.items.single { it.key == "form:frm_b" }
        assertEquals("Beta", root.sessionTitle)
        assertFalse(root.isChild)
        assertTrue(root is PendingItem.FormItem)

        assertFalse(state.toString().contains(sentinel))
    }

    @Test
    fun `an empty server shows an empty list`() = runTest {
        val presenter = presenter(FakeConnection(FakeOpencodeClient()))

        assertTrue(presenter.state.value.isEmpty)
    }

    @Test
    fun `new items are added at the end and kept in place when the list is read again`() = runTest {
        val client = FakeOpencodeClient().apply {
            sessions.seed(alpha)
            interactions.seedForm(form("frm_1", alpha.id))
        }
        val presenter = presenter(FakeConnection(client))

        val asked = permission("per_2", alpha.id)
        client.interactions.seedPermission(asked)
        client.events.emit(ServerEvent.PermissionAsked(asked))
        val created = form("frm_3", alpha.id)
        client.interactions.seedForm(created)
        client.events.emit(ServerEvent.FormCreated(created))
        runCurrent()

        assertEquals(listOf("form:frm_1", "permission:per_2", "form:frm_3"), presenter.state.value.keys())
        assertEquals("Alpha", presenter.state.value.items[1].sessionTitle)

        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        assertEquals(listOf("form:frm_1", "permission:per_2", "form:frm_3"), presenter.state.value.keys())
        assertFalse(presenter.state.value.refreshing)
    }

    @Test
    fun `an item of a session not seen yet is looked up for its title and parent`() = runTest {
        val client = FakeOpencodeClient().apply { sessions.seed(alpha) }
        val presenter = presenter(FakeConnection(client))

        client.sessions.seed(alphaChild)
        client.events.emit(ServerEvent.FormCreated(form("frm_new", alphaChild.id)))
        runCurrent()

        val item = presenter.state.value.items.single()
        assertEquals(alpha.id, item.parentId)
        assertEquals("Subagent", item.sessionTitle)
        assertTrue(item.isChild)
    }

    @Test
    fun `answered, cancelled and replied items are removed`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        client.events.emit(ServerEvent.FormCancelled(beta.id, FormId("frm_b")))
        runCurrent()
        assertEquals(setOf("permission:per_a_child", "permission:per_b"), presenter.state.value.keys().toSet())

        // A reply through the server sends the event itself.
        client.interactions.replyPermission(beta.id, PermissionRequestId("per_b"), PermissionDecision.Once)
        runCurrent()

        assertEquals(listOf("permission:per_a_child"), presenter.state.value.keys())
    }

    @Test
    fun `a deleted session takes its items and its children's with it`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.sessions.delete(alpha.id)
        runCurrent()

        assertEquals(setOf("form:frm_b", "permission:per_b"), presenter.state.value.keys().toSet())
    }

    @Test
    fun `a renamed session's items show the new title`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.events.emit(ServerEvent.SessionRenamed(beta.id, title = "Gamma"))
        runCurrent()

        val titles = presenter.state.value.items.filter { it.sessionId == beta.id }.map { it.sessionTitle }
        assertEquals(listOf("Gamma", "Gamma"), titles)
    }

    @Test
    fun `an answer that arrives during a read is not undone by the read`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        assertTrue(presenter.state.value.refreshing)

        // The stalled read already saw these; the events come before its answer.
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        val late = form("frm_late", beta.id)
        client.events.emit(ServerEvent.FormCreated(late))
        runCurrent()
        client.control.resume()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.refreshing)
        assertFalse("form:frm_a" in state.keys())
        assertEquals("form:frm_late", state.keys().last())
    }

    @Test
    fun `events held during a read are applied when the stream drops`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        client.events.emit(ServerEvent.FormCreated(form("frm_new", beta.id)))
        runCurrent()

        client.events.drop(ApiError.Network)
        runCurrent()

        val state = presenter.state.value
        assertEquals(NotifyFailure.Unreachable, state.error)
        assertFalse("form:frm_a" in state.keys())
        assertEquals("form:frm_new", state.keys().last())
        assertEquals("Beta", state.items.last().sessionTitle)
    }

    @Test
    fun `a read's answer and the events held during it are shown in one step`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            presenter.state.collect { seen += it.keys() }
        }

        client.interactions.seedForm(form("frm_server", alpha.id))
        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        // The stalled read still sees frm_a; the reply arrives before its answer.
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        runCurrent()
        client.control.resume()
        runCurrent()

        assertFalse("form:frm_a" in presenter.state.value.keys())
        assertTrue("form:frm_server" in presenter.state.value.keys())
        // Never the read's answer without the reply on top: a live event could otherwise slip in between.
        assertTrue(seen.none { "form:frm_a" in it && "form:frm_server" in it }, "seen: $seen")
    }

    @Test
    fun `a reconnect reads the titles of child sessions again`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))
        assertEquals("Subagent", presenter.state.value.items.single { it.isChild }.sessionTitle)

        client.events.drop(ApiError.Network)
        runCurrent()
        // Renamed while down: the event is missed.
        client.sessions.update(alphaChild.id, SessionUpdate(title = "Renamed"))
        client.events.reconnect()
        runCurrent()

        assertEquals("Renamed", presenter.state.value.items.single { it.isChild }.sessionTitle)
    }

    @Test
    fun `a lookup that answers after a rename keeps the newer title`() = runTest {
        val client = FakeOpencodeClient().apply { sessions.seed(alpha) }
        val presenter = presenter(FakeConnection(client))

        client.sessions.seed(alphaChild)
        client.control.stall()
        client.events.emit(ServerEvent.FormCreated(form("frm_child", alphaChild.id)))
        runCurrent()
        // The lookup is under way and answers with the title from before this rename.
        client.events.emit(ServerEvent.SessionRenamed(alphaChild.id, title = "Newer"))
        runCurrent()
        client.control.resume()
        runCurrent()

        val item = presenter.state.value.items.single()
        assertEquals("Newer", item.sessionTitle)
        assertEquals(alpha.id, item.parentId)
    }

    @Test
    fun `events held during a read are applied when the stream fails for good`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        runCurrent()
        client.events.fail(ApiError.Unauthorized)
        runCurrent()

        val state = presenter.state.value
        assertEquals(NotifyFailure.Unauthorized, state.error)
        assertFalse("form:frm_a" in state.keys())
        assertEquals(3, state.items.size)
    }

    @Test
    fun `items of a read with many sessions all keep their session`() = runTest {
        val count = 600
        val client = FakeOpencodeClient().apply {
            repeat(count) { i ->
                sessions.seed(Session(id = SessionId("ses_$i"), title = "Session $i"))
                interactions.seedForm(form("frm_$i", SessionId("ses_$i")))
            }
        }
        val presenter = presenter(FakeConnection(client))

        val items = presenter.state.value.items
        assertEquals(count, items.size)
        assertEquals(emptyList(), items.filter { it.sessionTitle != "Session ${it.sessionId.value.removePrefix("ses_")}" })
    }

    @Test
    fun `a child whose parent is deleted while it is looked up loses its items`() = runTest {
        val fake = FakeOpencodeClient().apply { sessions.seed(alpha) }
        val client = LateLookups(fake)
        val presenter = presenter(FakeConnection(client))

        fake.sessions.seed(alphaChild)
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        fake.events.emit(ServerEvent.FormCreated(form("frm_child", alphaChild.id)))
        runCurrent()
        assertEquals(1, presenter.state.value.items.size)

        // Only the parent's deletion arrives before the lookup answers.
        fake.events.emit(ServerEvent.SessionDeleted(alpha.id))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(presenter.state.value.items.isEmpty())
    }

    @Test
    fun `a lookup from before a reconnect does not overwrite what the reconnect read`() = runTest {
        val fake = FakeOpencodeClient().apply { sessions.seed(alpha) }
        val client = LateLookups(fake)
        val presenter = presenter(FakeConnection(client))

        fake.sessions.seed(alphaChild)
        val created = form("frm_child", alphaChild.id)
        fake.interactions.seedForm(created)
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        fake.events.emit(ServerEvent.FormCreated(created))
        runCurrent()

        fake.events.drop(ApiError.Network)
        runCurrent()
        fake.sessions.update(alphaChild.id, SessionUpdate(title = "Renamed"))
        fake.events.reconnect()
        runCurrent()
        assertEquals("Renamed", presenter.state.value.items.single().sessionTitle)

        // The lookup from before the drop answers last, with the old title.
        gate.complete(Unit)
        runCurrent()

        assertEquals("Renamed", presenter.state.value.items.single().sessionTitle)
    }

    @Test
    fun `a stream that ends stops its reads and lookups, applies held events, and Refresh reopens it`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))
        val orphan = Session(id = SessionId("ses_orphan"), title = "Orphan")
        client.sessions.seed(orphan)

        client.control.stall()
        // A lookup and a read, both stalled.
        client.events.emit(ServerEvent.FormCreated(form("frm_orphan", orphan.id)))
        runCurrent()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        runCurrent()

        client.events.end()
        runCurrent()

        assertFalse("form:frm_a" in presenter.state.value.keys())
        assertFalse(presenter.state.value.refreshing)

        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        assertEquals(2, client.events.connectionsOpened)
        assertEquals(1, client.events.activeConnections.value)
    }

    @Test
    fun `an item created for a deleted session is not added`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.events.emit(ServerEvent.SessionDeleted(beta.id))
        client.events.emit(ServerEvent.FormCreated(form("frm_late", beta.id)))
        runCurrent()

        assertFalse(presenter.state.value.items.any { it.sessionId == beta.id })
    }

    @Test
    fun `an item created for a child of a deleted session is not added`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        // Only the parent's deletion arrives; the child is known from the read.
        client.events.emit(ServerEvent.SessionDeleted(alpha.id))
        client.events.emit(ServerEvent.PermissionAsked(permission("per_late", alphaChild.id)))
        runCurrent()

        assertEquals(setOf("form:frm_b", "permission:per_b"), presenter.state.value.keys().toSet())
    }

    @Test
    fun `items held during a read for a session deleted meanwhile are not added`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        client.events.emit(ServerEvent.SessionDeleted(alpha.id))
        client.events.emit(ServerEvent.FormCreated(form("frm_late", alphaChild.id)))
        runCurrent()
        // The read answers from before the deletion.
        client.control.resume()
        runCurrent()

        assertEquals(setOf("form:frm_b", "permission:per_b"), presenter.state.value.keys().toSet())
    }

    @Test
    fun `a new generation starts over with the new connection's items`() = runTest {
        val first = server()
        val connection = FakeConnection(first, generation = 1)
        val presenter = presenter(connection)
        assertEquals(4, presenter.state.value.items.size)

        val second = FakeOpencodeClient().apply {
            sessions.seed(alpha)
            interactions.seedPermission(permission("per_other", alpha.id))
        }
        second.control.stall()
        connection.switch(second, generation = 2)
        runCurrent()

        // Nothing of the old server is shown while the new one is read.
        assertEquals(PendingState(loading = true), presenter.state.value)

        second.control.resume()
        runCurrent()
        assertEquals(listOf("permission:per_other"), presenter.state.value.keys())

        second.events.emit(ServerEvent.FormCreated(form("frm_second", alpha.id)))
        runCurrent()
        assertEquals(listOf("permission:per_other", "form:frm_second"), presenter.state.value.keys())
    }

    @Test
    fun `a read of an older generation that answers late is dropped`() = runTest {
        val first = server()
        first.control.stall()
        val connection = FakeConnection(first, generation = 1)
        val presenter = presenter(connection)
        assertTrue(presenter.state.value.loading)

        val second = FakeOpencodeClient()
        connection.switch(second, generation = 2)
        runCurrent()
        first.control.resume()
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
        assertEquals(0, first.events.activeConnections.value)
    }

    @Test
    fun `without a connection the list is empty and Connect opens the server list`() = runTest {
        val connection = FakeConnection(server())
        val presenter = presenter(connection)
        assertEquals(4, presenter.state.value.items.size)

        connection.switch(null, generation = 0)
        runCurrent()

        assertEquals(PendingState(loading = false, error = NotifyFailure.NotConnected), presenter.state.value)

        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        assertEquals(NotifyFailure.NotConnected, presenter.state.value.error)

        presenter.onIntent(PendingIntent.Connect)
        assertEquals(PendingEvent.OpenServerList, presenter.events.first())
    }

    @Test
    fun `a connection that comes back is read again`() = runTest {
        val connection = FakeConnection(null)
        val presenter = presenter(connection)
        assertEquals(NotifyFailure.NotConnected, presenter.state.value.error)

        connection.switch(server(), generation = 3)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(4, presenter.state.value.items.size)
    }

    @Test
    fun `a dropped stream keeps the items and reads them again on reconnect`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.events.drop(ApiError.Network)
        runCurrent()
        assertEquals(NotifyFailure.Unreachable, presenter.state.value.error)
        assertEquals(4, presenter.state.value.items.size)

        // Missed while down: the server replays nothing.
        client.interactions.replyPermission(beta.id, PermissionRequestId("per_b"), PermissionDecision.Reject)
        client.interactions.seedForm(form("frm_missed", alpha.id))

        client.events.reconnect()
        runCurrent()

        val state = presenter.state.value
        assertNull(state.error)
        assertFalse(state.loading)
        assertFalse("permission:per_b" in state.keys())
        assertTrue("form:frm_missed" in state.keys())
    }

    @Test
    fun `a failed read keeps the items, shows the failure without server text, and Refresh retries`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.failNext(ApiError.Server(500, secret))
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        val failed = presenter.state.value
        assertEquals(NotifyFailure.ServerFailed, failed.error)
        assertEquals(4, failed.items.size)
        assertFalse(failed.refreshing)
        assertFalse(failed.toString().contains(sentinel))

        presenter.onIntent(PendingIntent.DismissError)
        assertNull(presenter.state.value.error)

        client.interactions.seedForm(form("frm_more", alpha.id))
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(5, presenter.state.value.items.size)
    }

    @Test
    fun `a folder that cannot be read keeps its items and does not fail the read`() = runTest {
        val fake = server()
        val client = BrokenFolders(fake)
        val presenter = presenter(FakeConnection(client))
        assertEquals(4, presenter.state.value.items.size)

        client.broken += beta.directory
        // While the folder cannot be read, its server side changes, and the default folder's too.
        fake.interactions.replyPermission(beta.id, PermissionRequestId("per_b"), PermissionDecision.Once)
        fake.interactions.seedForm(form("frm_b_new", beta.id))
        fake.interactions.seedForm(form("frm_a_new", alpha.id))
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        val state = presenter.state.value
        assertNull(state.error)
        assertFalse(state.refreshing)
        // The broken folder's items stay as they were shown; the others are read.
        assertEquals(
            setOf("form:frm_a", "permission:per_a_child", "form:frm_b", "form:frm_a_new"),
            state.keys().toSet(),
        )

        client.broken.clear()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        assertEquals(
            setOf("form:frm_a", "permission:per_a_child", "form:frm_b", "form:frm_b_new", "form:frm_a_new"),
            presenter.state.value.keys().toSet(),
        )
    }

    @Test
    fun `a folder that cannot be read on the first read is left out without an error`() = runTest {
        val client = BrokenFolders(server())
        client.broken += beta.directory
        val presenter = presenter(FakeConnection(client))

        val state = presenter.state.value
        assertNull(state.error)
        assertFalse(state.loading)
        assertEquals(setOf("form:frm_a", "permission:per_a_child"), state.keys().toSet())
    }

    @Test
    fun `the default folder that cannot be read fails the read and keeps the items`() = runTest {
        val client = BrokenFolders(server())
        val presenter = presenter(FakeConnection(client))

        client.broken += null
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        val state = presenter.state.value
        assertEquals(NotifyFailure.ServerFailed, state.error)
        assertEquals(4, state.items.size)
        assertFalse(state.refreshing)
    }

    @Test
    fun `root sessions that cannot be listed fail the read and keep the items`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.control.failNext(ApiError.Timeout)
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        assertEquals("sessions.list", client.control.calls.last())
        val state = presenter.state.value
        assertEquals(NotifyFailure.Unreachable, state.error)
        assertEquals(4, state.items.size)
    }

    @Test
    fun `a first read that fails stops loading`() = runTest {
        val client = server()
        client.control.failNext(ApiError.Unauthorized)
        val presenter = presenter(FakeConnection(client))

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(NotifyFailure.Unauthorized, state.error)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a failed stream shows its failure and Refresh opens it again`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))
        assertEquals(1, client.events.connectionsOpened)

        client.events.fail(ApiError.Unauthorized)
        runCurrent()
        assertEquals(NotifyFailure.Unauthorized, presenter.state.value.error)
        assertEquals(4, presenter.state.value.items.size)
        assertEquals(0, client.events.activeConnections.value)

        client.events.fail(ApiError.Unauthorized) // No stream open: nothing to fail.
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()

        assertEquals(2, client.events.connectionsOpened)
        assertNull(presenter.state.value.error)

        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        runCurrent()
        assertEquals(3, presenter.state.value.items.size)
    }

    @Test
    fun `a closed client shows NotConnected`() = runTest {
        val client = server()
        val presenter = presenter(FakeConnection(client))

        client.close()
        runCurrent()

        assertEquals(NotifyFailure.NotConnected, presenter.state.value.error)
    }

    @Test
    fun `Open, Connect and Close send their events`() = runTest {
        val presenter = presenter(FakeConnection(server()))
        val child = presenter.state.value.items.single { it.isChild }
        val root = presenter.state.value.items.first { !it.isChild }

        presenter.onIntent(PendingIntent.Open(child))
        assertEquals(PendingEvent.OpenSession(alphaChild.id, isChild = true), presenter.events.first())
        presenter.onIntent(PendingIntent.Open(root))
        assertEquals(PendingEvent.OpenSession(root.sessionId, isChild = false), presenter.events.first())
        presenter.onIntent(PendingIntent.Close)
        assertEquals(PendingEvent.Close, presenter.events.first())
    }

    @Test
    fun `cancelling the scope stops the stream and every read`() = runTest {
        val client = server()
        val connection = FakeConnection(client)
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val presenter = presenter(connection, scope)
        assertEquals(1, client.events.activeConnections.value)

        client.control.stall()
        presenter.onIntent(PendingIntent.Refresh)
        runCurrent()
        scope.cancel()
        runCurrent()

        assertEquals(0, client.events.activeConnections.value)
        val calls = client.control.calls.size
        val shown = presenter.state.value
        client.control.resume()
        client.events.emit(ServerEvent.FormReplied(alpha.id, FormId("frm_a")))
        connection.switch(FakeOpencodeClient(), generation = 2)
        runCurrent()

        assertEquals(calls, client.control.calls.size)
        assertEquals(shown, presenter.state.value)
    }

    @Test
    fun `the stream asks only for the events the list follows`() = runTest {
        val client = server()
        presenter(FakeConnection(client))

        val types = client.events.requestedTypes.single()
        assertTrue(
            types.containsAll(
                setOf("form.created", "form.replied", "form.cancelled", "permission.asked", "permission.replied", "session.deleted"),
            ),
        )
    }
}
