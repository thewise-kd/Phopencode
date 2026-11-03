package io.github.thewisekd.phopencode.core.testing.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.api.VcsBranchInfo
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.TestScope

/**
 * Arranges a server for the API contracts. Everything here is the server's side: a real
 * implementation's harness backs it with a scripted HTTP server, the fake's with its own state.
 */
interface ApiHarness {
    val client: OpencodeClient

    /** The server's default folder. */
    val defaultFolder: Location

    suspend fun givenServerVersion(version: String)

    /** The next call fails with [error], as the server's answer. */
    suspend fun givenFailure(error: ApiError)

    /** The server stops answering, so calls made from now on stay running. */
    suspend fun stallCalls()

    /** Waits until [count] calls reached the server and wait for an answer. */
    suspend fun awaitRunningCalls(count: Int)

    suspend fun givenSession(session: Session)

    /** @param messages Oldest first. */
    suspend fun givenMessages(sessionId: SessionId, messages: List<Message>)

    suspend fun givenRunning(sessionId: SessionId)

    suspend fun givenForm(form: Form)

    suspend fun givenPermission(request: PermissionRequest)

    suspend fun givenCommands(commands: List<Command>, location: Location?)

    suspend fun givenFiles(location: Location, files: List<FileEntry>)

    suspend fun givenBranches(location: Location, branches: List<String>)

    suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>)

    /** Sends [event] to every open event connection. */
    suspend fun serveEvent(event: ServerEvent)

    /** Sends a heartbeat to every open event connection. */
    suspend fun serveHeartbeat()

    /** Ends every event connection, as a network failure would. */
    suspend fun dropEventConnections()

    /** Accepts the connections of the collectors that are reconnecting. */
    suspend fun acceptReconnects()

    /** Answers every event connection, and new ones, with [error], which retrying cannot help. */
    suspend fun refuseEventConnections(error: ApiError)

    /** Waits until [count] event connections are open. */
    suspend fun awaitEventConnections(count: Int)

    /** The event connections open now. */
    suspend fun eventConnections(): Int
}

private val SESSION = SessionId("session-a")
private val OTHER_SESSION = SessionId("session-b")
private val CHILD_SESSION = SessionId("session-a-child")
private val GRANDCHILD_SESSION = SessionId("session-a-grandchild")
private val FOLDER = Location("/srv/project")
private val OTHER_FOLDER = Location("/srv/other")

private fun user(id: String, sessionId: SessionId = SESSION) = Message.User(MessageId(id), sessionId, text = id)

private fun question(id: String = "form-a", sessionId: SessionId = SESSION) = Form(
    id = FormId(id),
    sessionId = sessionId,
    kind = FormKind.Question,
    fields = listOf(FormField.FreeText(key = "q0", required = true)),
)

private fun permission(id: String = "permission-a", sessionId: SessionId = SESSION) =
    PermissionRequest(PermissionRequestId(id), sessionId, action = "bash", save = listOf("*"))

private val ANSWER = mapOf("q0" to DataValue.Text("yes"))

/** Every call of a client. Against a closed client none of them needs what it names to exist. */
private val ALL_CALLS: List<Pair<String, suspend (OpencodeClient) -> Unit>> = listOf(
    "serverInfo" to { it.serverInfo() },
    "sessions.create" to { it.sessions.create() },
    "sessions.list" to { it.sessions.list() },
    "sessions.get" to { it.sessions.get(SESSION) },
    "sessions.delete" to { it.sessions.delete(SESSION) },
    "sessions.update" to { it.sessions.update(SESSION, SessionUpdate(title = "t")) },
    "sessions.stop" to { it.sessions.stop(SESSION) },
    "sessions.fork" to { it.sessions.fork(SESSION) },
    "sessions.compact" to { it.sessions.compact(SESSION) },
    "sessions.activeStatuses" to { it.sessions.activeStatuses() },
    "messages.prompt" to { it.messages.prompt(SESSION, Prompt("hello")) },
    "messages.list" to { it.messages.list(SESSION) },
    "messages.command" to { it.messages.command(SESSION, "name") },
    "messages.switchAgent" to { it.messages.switchAgent(SESSION, "build") },
    "messages.switchModel" to { it.messages.switchModel(SESSION, ModelRef("provider", "model")) },
    "messages.turnDiff" to { it.messages.turnDiff(SESSION) },
    "messages.stageRevert" to { it.messages.stageRevert(SESSION, MessageId("m")) },
    "messages.commitRevert" to { it.messages.commitRevert(SESSION) },
    "messages.cancelRevert" to { it.messages.cancelRevert(SESSION) },
    "interactions.listForms" to { it.interactions.listForms() },
    "interactions.replyForm" to { it.interactions.replyForm(SESSION, FormId("f"), ANSWER) },
    "interactions.cancelForm" to { it.interactions.cancelForm(SESSION, FormId("f")) },
    "interactions.listPermissions" to { it.interactions.listPermissions() },
    "interactions.replyPermission" to {
        it.interactions.replyPermission(SESSION, PermissionRequestId("p"), PermissionDecision.Once)
    },
    "catalog.skills" to { it.catalog.skills() },
    "catalog.commands" to { it.catalog.commands() },
    "catalog.agents" to { it.catalog.agents() },
    "catalog.models" to { it.catalog.models() },
    "catalog.defaultModel" to { it.catalog.defaultModel() },
    "catalog.providers" to { it.catalog.providers() },
    "catalog.mcpServers" to { it.catalog.mcpServers() },
    "catalog.plugins" to { it.catalog.plugins() },
    "vcs.current" to { it.vcs.current(FOLDER) },
    "vcs.status" to { it.vcs.status(FOLDER) },
    "vcs.diff" to { it.vcs.diff(FOLDER) },
    "vcs.branches" to { it.vcs.branches(FOLDER) },
    "fs.find" to { it.fs.find(FOLDER, "x") },
)

/** The promises on [OpencodeClient]. */
fun opencodeClientContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("serverInfo reports the server's version") {
        val harness = newHarness()
        harness.givenServerVersion("${OpencodeClient.MIN_SERVER_VERSION}.5")
        expectEqual("${OpencodeClient.MIN_SERVER_VERSION}.5", harness.client.serverInfo().version, "version")
    }
    case("serverInfo accepts exactly the oldest supported version") {
        val harness = newHarness()
        harness.givenServerVersion(OpencodeClient.MIN_SERVER_VERSION)
        expectEqual(OpencodeClient.MIN_SERVER_VERSION, harness.client.serverInfo().version, "version")
    }
    case("serverInfo fails with VersionTooOld below the oldest supported version") {
        val harness = newHarness()
        harness.givenServerVersion("2.1.9")
        expectApiError(ApiError.VersionTooOld("2.1.9"), "serverInfo") { harness.client.serverInfo() }
    }
    case("a version is compared by number, not as text") {
        val harness = newHarness()
        harness.givenServerVersion("2.10.0")
        expectEqual("2.10.0", harness.client.serverInfo().version, "version")
    }
    case("a newer major version is a warning, not an error") {
        val harness = newHarness()
        harness.givenServerVersion("99.0.0")
        expectThat(harness.client.serverInfo().isNewerMajor, "99.0.0 is a newer major version")
    }
    case("a server failure arrives as ApiException with its typed error") {
        val harness = newHarness()
        harness.givenFailure(ApiError.Unauthorized)
        expectApiError(ApiError.Unauthorized, "list") { harness.client.sessions.list() }
    }
    case("the error does not stick to later calls") {
        val harness = newHarness()
        harness.givenFailure(ApiError.Server(503))
        expectApiError(ApiError.Server(503), "first") { harness.client.sessions.list() }
        harness.client.sessions.list()
    }
    case("cancelling a running call passes through as cancellation") {
        val harness = newHarness()
        harness.stallCalls()
        val call = start { harness.client.sessions.list() }
        harness.awaitRunningCalls(1)
        call.cancel()
        val failure = call.failure("cancelled call")
        expectThat(failure is CancellationException, "Cancelling must not become <$failure>")
    }
    case("close can be called twice and throws nothing") {
        val client = newHarness().client
        client.close()
        client.close()
    }
    ALL_CALLS.forEach { (name, call) ->
        case("$name after close fails with Closed") {
            val client = newHarness().client
            client.close()
            expectApiError(ApiError.Closed, name) { call(client) }
        }
    }
    case("every running call fails with Closed on close") {
        val harness = newHarness()
        harness.stallCalls()
        val running = ALL_CALLS.map { (name, call) -> name to start { call(harness.client) } }
        harness.awaitRunningCalls(ALL_CALLS.size)
        harness.client.close()
        running.forEach { (name, call) ->
            val failure = call.failure(name)
            expectThat(failure is ApiException && failure.error == ApiError.Closed, "$name failed with <$failure>")
        }
    }
    case("a closed client answers Closed, whatever the server would have said") {
        val harness = newHarness()
        harness.givenFailure(ApiError.Unauthorized)
        harness.client.close()
        expectApiError(ApiError.Closed, "list") { harness.client.sessions.list() }
    }
    case("an event collector fails with Closed on close, and does not reconnect") {
        val harness = newHarness()
        val seen = collect(harness.client.events.events())
        seen.next("Connected")
        harness.client.close()
        val failure = seen.failure("collector")
        expectThat(failure is ApiException && failure.error == ApiError.Closed, "Collector failed with <$failure>")
        expectEqual(0, harness.eventConnections(), "connections after close")
    }
    case("an idle collector and one stuck behind a drop both fail with Closed") {
        val harness = newHarness()
        val idle = collect(harness.client.events.events())
        val dropped = collect(harness.client.events.events())
        idle.next("Connected")
        dropped.next("Connected")
        harness.dropEventConnections()
        idle.next("Disconnected")
        dropped.next("Disconnected")
        harness.client.close()
        listOf(idle, dropped).forEach {
            val failure = it.failure("collector")
            expectThat(failure is ApiException && failure.error == ApiError.Closed, "Collector failed with <$failure>")
        }
    }
    case("collecting events after close fails with Closed and connects nothing") {
        val harness = newHarness()
        harness.client.close()
        expectApiError(ApiError.Closed, "events") { harness.client.events.events().collect { contractFailure("Got <$it>") } }
        expectEqual(0, harness.eventConnections(), "connections")
    }
}

private suspend fun TestScope.connected(harness: ApiHarness, types: Set<String> = emptySet()): Collection<ServerEvent> {
    val seen = collect(harness.client.events.events(types))
    expectThat(seen.next("first event") is ServerEvent.Connected, "A connection must start with Connected")
    return seen
}

/** The promises on [io.github.thewisekd.phopencode.core.api.EventApi.events]. */
fun eventApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("the flow is cold: nothing connects until it is collected") {
        val harness = newHarness()
        harness.client.events.events()
        expectEqual(0, harness.eventConnections(), "connections")
    }
    case("every connection starts with Connected") {
        connected(newHarness())
    }
    case("each collector gets a connection of its own") {
        val harness = newHarness()
        val first = connected(harness)
        val second = connected(harness)
        harness.awaitEventConnections(2)
        val event = ServerEvent.SessionDeleted(SESSION)
        harness.serveEvent(event)
        expectEqual<ServerEvent>(event, first.next("first collector"), "first collector")
        expectEqual<ServerEvent>(event, second.next("second collector"), "second collector")
    }
    case("cancelling a collection closes its connection and leaves the others") {
        val harness = newHarness()
        val first = connected(harness)
        val second = connected(harness)
        first.cancel()
        first.end("cancelled collection")
        harness.awaitEventConnections(1)
        expectEqual(1, harness.eventConnections(), "connections")
        val event = ServerEvent.SessionDeleted(SESSION)
        harness.serveEvent(event)
        expectEqual<ServerEvent>(event, second.next("remaining collector"), "remaining collector")
    }
    case("events of all sessions arrive, child sessions included") {
        val harness = newHarness()
        val seen = connected(harness)
        val parent = ServerEvent.ExecutionStarted(SESSION)
        val child = ServerEvent.ExecutionStarted(CHILD_SESSION)
        harness.serveEvent(parent)
        harness.serveEvent(child)
        expectEqual<ServerEvent>(parent, seen.next("parent"), "parent")
        expectEqual<ServerEvent>(child, seen.next("child"), "child")
    }
    case("types narrows what arrives") {
        val harness = newHarness()
        val seen = connected(harness, setOf("form.cancelled"))
        val wanted = ServerEvent.FormCancelled(SESSION, FormId("form-a"))
        harness.serveEvent(ServerEvent.ExecutionStarted(SESSION))
        harness.serveEvent(wanted)
        expectEqual<ServerEvent>(wanted, seen.next("wanted event"), "wanted event")
    }
    case("types does not hold back Connected or Disconnected") {
        val harness = newHarness()
        val seen = connected(harness, setOf("form.cancelled"))
        harness.dropEventConnections()
        expectThat(seen.next("drop") is ServerEvent.Disconnected, "A drop must emit Disconnected")
        harness.acceptReconnects()
        expectThat(seen.next("reconnect") is ServerEvent.Connected, "A reconnect must emit Connected")
    }
    case("a drop emits Disconnected, a reconnect Connected, and the flow goes on") {
        val harness = newHarness()
        val seen = connected(harness)
        harness.dropEventConnections()
        expectThat(seen.next("drop") is ServerEvent.Disconnected, "A drop must emit Disconnected")
        expectThat(!seen.isFinished, "The flow must not complete on a drop")
        harness.acceptReconnects()
        expectThat(seen.next("reconnect") is ServerEvent.Connected, "A reconnect must emit Connected")
        val event = ServerEvent.ExecutionStarted(SESSION)
        harness.serveEvent(event)
        expectEqual<ServerEvent>(event, seen.next("event after the reconnect"), "event after the reconnect")
    }
    case("the server replays nothing that happened during a drop") {
        val harness = newHarness()
        val seen = connected(harness)
        harness.dropEventConnections()
        seen.next("Disconnected")
        harness.serveEvent(ServerEvent.ExecutionStarted(OTHER_SESSION))
        harness.acceptReconnects()
        seen.next("Connected")
        val event = ServerEvent.ExecutionStarted(SESSION)
        harness.serveEvent(event)
        expectEqual<ServerEvent>(event, seen.next("event after the reconnect"), "event after the reconnect")
    }
    case("an unknown event does not fail the flow") {
        val harness = newHarness()
        val seen = connected(harness)
        val unknown = ServerEvent.Unknown("something.new")
        val event = ServerEvent.ExecutionStarted(SESSION)
        harness.serveEvent(unknown)
        harness.serveEvent(event)
        expectEqual<ServerEvent>(unknown, seen.next("unknown event"), "unknown event")
        expectEqual<ServerEvent>(event, seen.next("next event"), "next event")
    }
    case("heartbeats are not emitted") {
        val harness = newHarness()
        val seen = connected(harness)
        val event = ServerEvent.ExecutionStarted(SESSION)
        harness.serveHeartbeat()
        harness.serveEvent(event)
        expectEqual<ServerEvent>(event, seen.next("event after the heartbeat"), "event after the heartbeat")
    }
    case("the flow fails with ApiException when retrying cannot help") {
        val harness = newHarness()
        val seen = connected(harness)
        harness.refuseEventConnections(ApiError.Unauthorized)
        val failure = seen.failure("collector")
        expectThat(failure is ApiException && failure.error == ApiError.Unauthorized, "Collector failed with <$failure>")
        expectApiError(ApiError.Unauthorized, "new collector") { harness.client.events.events().collect {} }
    }
}

private suspend fun ApiHarness.givenFamily() {
    givenSession(Session(SESSION))
    givenSession(Session(CHILD_SESSION, parentId = SESSION))
    givenSession(Session(GRANDCHILD_SESSION, parentId = CHILD_SESSION))
    givenSession(Session(OTHER_SESSION))
}

private fun Page<Session>.ids() = items.map { it.id }.toSet()

/** Follows the cursors as a caller must: only [Page.isEnd] ends the list. */
private suspend fun <T> walk(fetch: suspend (PageCursor?) -> Page<T>): List<T> {
    val items = mutableListOf<T>()
    var cursor: PageCursor? = null
    repeat(MAX_PAGES) {
        val page = fetch(cursor)
        items += page.items
        if (page.isEnd) return items
        cursor = page.nextCursor
    }
    contractFailure("The list did not end after $MAX_PAGES pages")
}

private const val MAX_PAGES = 50

/** The promises on [io.github.thewisekd.phopencode.core.api.SessionApi]. */
fun sessionApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("create gives a session that get and list find") {
        val sessions = newHarness().client.sessions
        val created = sessions.create(CreateSessionRequest(title = "title"))
        expectEqual("title", sessions.get(created.id).title, "title")
        expectEqual(setOf(created.id), sessions.list().ids(), "list")
    }
    case("parentId makes the new session a child") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val child = harness.client.sessions.create(CreateSessionRequest(parentId = SESSION))
        expectEqual(SESSION, child.parentId, "parent")
        expectThat(!child.isRoot, "A child is not a root")
    }
    case("get of a missing session fails with NotFound") {
        expectApiError(ApiError.NotFound, "get") { newHarness().client.sessions.get(SESSION) }
    }
    case("delete removes the session and all its child sessions") {
        val harness = newHarness()
        harness.givenFamily()
        harness.client.sessions.delete(SESSION)
        expectEqual(setOf(OTHER_SESSION), harness.client.sessions.list().ids(), "remaining sessions")
        expectApiError(ApiError.NotFound, "get of a child") { harness.client.sessions.get(GRANDCHILD_SESSION) }
    }
    case("delete of a missing session fails with NotFound") {
        expectApiError(ApiError.NotFound, "delete") { newHarness().client.sessions.delete(SESSION) }
    }
    case("update changes the named fields, and a null field stays") {
        val harness = newHarness()
        val metadata = mapOf("key" to DataValue.Text("value"))
        harness.givenSession(Session(SESSION, title = "old", metadata = metadata))
        harness.client.sessions.update(SESSION, SessionUpdate(title = "new"))
        val updated = harness.client.sessions.get(SESSION)
        expectEqual("new", updated.title, "title")
        expectEqual(metadata, updated.metadata, "metadata")
        harness.client.sessions.update(SESSION, SessionUpdate(archived = true))
        expectEqual("new", harness.client.sessions.get(SESSION).title, "title after archiving")
    }
    case("un-archiving sends SessionUnarchived") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, archived = true))
        val seen = connected(harness, setOf("session.unarchived"))
        harness.client.sessions.update(SESSION, SessionUpdate(archived = false))
        expectEqual<ServerEvent>(ServerEvent.SessionUnarchived(SESSION), seen.next("event"), "event")
        expectThat(!harness.client.sessions.get(SESSION).archived, "The session must not be archived any more")
    }
    case("archived sessions are listed by default, and false hides them") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, archived = true))
        harness.givenSession(Session(OTHER_SESSION))
        expectEqual(setOf(SESSION, OTHER_SESSION), harness.client.sessions.list().ids(), "default")
        expectEqual(setOf(OTHER_SESSION), harness.client.sessions.list(SessionQuery(archived = false)).ids(), "false")
        expectEqual(setOf(SESSION), harness.client.sessions.list(SessionQuery(archived = true)).ids(), "true")
    }
    case("the parent filter gives everything, the roots or direct children") {
        val harness = newHarness()
        harness.givenFamily()
        val sessions = harness.client.sessions
        expectEqual(4, sessions.list().items.size, "any")
        expectEqual(
            setOf(SESSION, OTHER_SESSION),
            sessions.list(SessionQuery(parent = SessionParentFilter.RootsOnly)).ids(),
            "roots",
        )
        expectEqual(
            setOf(CHILD_SESSION),
            sessions.list(SessionQuery(parent = SessionParentFilter.ChildrenOf(SESSION))).ids(),
            "direct children",
        )
    }
    case("the folder narrows a list") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, directory = FOLDER.directory))
        harness.givenSession(Session(OTHER_SESSION, directory = OTHER_FOLDER.directory))
        val listed = harness.client.sessions.list(SessionQuery(directory = FOLDER.directory))
        expectEqual(setOf(SESSION), listed.ids(), "folder")
    }
    case("following nextCursor until isEnd gives every session once, in the asked order") {
        val harness = newHarness()
        val ids = (0 until 5).map { SessionId("session-$it") }
        ids.forEach { harness.givenSession(Session(it)) }
        val walked = walk { cursor ->
            harness.client.sessions.list(SessionQuery(limit = 2, order = SortOrder.Ascending, cursor = cursor))
        }
        expectEqual(ids, walked.map { it.id }, "ascending")
        val reversed = walk { cursor ->
            harness.client.sessions.list(SessionQuery(limit = 2, order = SortOrder.Descending, cursor = cursor))
        }
        expectEqual(ids.reversed(), reversed.map { it.id }, "descending")
    }
    case("stop interrupts running work, and the session stays") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenRunning(SESSION)
        expectEqual(true, harness.client.sessions.stop(SESSION), "stop")
        expectEqual(SESSION, harness.client.sessions.get(SESSION).id, "session")
        expectEqual(emptyMap(), harness.client.sessions.activeStatuses(), "active sessions")
    }
    case("stop says when nothing was running") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        expectEqual(false, harness.client.sessions.stop(SESSION), "stop")
    }
    case("fork copies the history, all of it or up to a message") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val messages = listOf(user("m1"), user("m2"), user("m3"))
        harness.givenMessages(SESSION, messages)
        val ascending = MessageQuery(order = SortOrder.Ascending)
        val all = harness.client.sessions.fork(SESSION)
        expectThat(all.id != SESSION, "A fork is a new session")
        expectEqual(
            messages.map { it.text },
            harness.client.messages.list(all.id, ascending).items.map { (it as Message.User).text },
            "full copy",
        )
        val part = harness.client.sessions.fork(SESSION, before = MessageId("m2"))
        expectEqual(listOf("m1"), harness.client.messages.list(part.id, ascending).items.map { (it as Message.User).text }, "part")
        expectEqual(3, harness.client.messages.list(SESSION).items.size, "original")
    }
    case("compact returns the accepted inbox item, which is not the result") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val item = harness.client.sessions.compact(SESSION)
        expectEqual(SESSION, item.sessionId, "session")
        expectEqual<InboxContent>(InboxContent.Compaction, item.content, "content")
        expectEqual(SESSION, harness.client.sessions.get(SESSION).id, "session after compact")
    }
    case("activeStatuses names running sessions, child sessions included, and leaves idle ones out") {
        val harness = newHarness()
        harness.givenFamily()
        harness.givenRunning(SESSION)
        harness.givenRunning(CHILD_SESSION)
        expectEqual(
            mapOf<SessionId, SessionActivity>(SESSION to SessionActivity.Running, CHILD_SESSION to SessionActivity.Running),
            harness.client.sessions.activeStatuses(),
            "active sessions",
        )
    }
}

/** The promises on [io.github.thewisekd.phopencode.core.api.MessageApi]. */
fun messageApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("prompt returns the inbox item, with steer as the default delivery") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val item = harness.client.messages.prompt(SESSION, Prompt("hello"))
        expectEqual(SESSION, item.sessionId, "session")
        expectEqual<InboxContent>(InboxContent.User("hello"), item.content, "content")
        expectEqual(PromptDelivery.Steer, item.delivery, "delivery")
    }
    case("prompt keeps the delivery and attachments it was given") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val prompt = Prompt("hello", listOf("a.txt"), listOf("agent"), listOf("skill"), PromptDelivery.Queue)
        val item = harness.client.messages.prompt(SESSION, prompt)
        expectEqual<InboxContent>(InboxContent.User("hello", listOf("a.txt"), listOf("agent"), listOf("skill")), item.content, "content")
        expectEqual(PromptDelivery.Queue, item.delivery, "delivery")
    }
    case("prompt to a missing session fails with NotFound") {
        expectApiError(ApiError.NotFound, "prompt") { newHarness().client.messages.prompt(SESSION, Prompt("hello")) }
    }
    case("list is newest first unless asked otherwise") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1"), user("m2")))
        val texts = { page: Page<Message> -> page.items.map { it.id.value } }
        expectEqual(listOf("m2", "m1"), texts(harness.client.messages.list(SESSION)), "default")
        expectEqual(
            listOf("m1", "m2"),
            texts(harness.client.messages.list(SESSION, MessageQuery(order = SortOrder.Ascending))),
            "ascending",
        )
    }
    case("the server's type narrows a list") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val assistant = Message.Assistant(MessageId("m2"), SESSION)
        harness.givenMessages(SESSION, listOf(user("m1"), assistant))
        val listed = harness.client.messages.list(SESSION, MessageQuery(type = "user")).items
        expectEqual(listOf("m1"), listed.map { it.id.value }, "user messages")
    }
    case("following nextCursor until isEnd gives every message once") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val messages = (0 until 5).map { user("m$it") }
        harness.givenMessages(SESSION, messages)
        val walked = walk { cursor ->
            harness.client.messages.list(SESSION, MessageQuery(limit = 2, order = SortOrder.Ascending, cursor = cursor))
        }
        expectEqual(messages.map { it.id }, walked.map { it.id }, "messages")
    }
    case("a list of exactly one page still ends") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1"), user("m2")))
        val walked = walk { harness.client.messages.list(SESSION, MessageQuery(limit = 2, cursor = it)) }
        expectEqual(2, walked.size, "messages")
    }
    case("switchModel accepts a model that does not exist") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.client.messages.switchModel(SESSION, ModelRef("no-such-provider", "no-such-model"))
    }
    case("turnDiff with no start gives the newest user turn") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1"), user("m2")))
        val older = listOf(FileDiff(VcsFile("older", VcsFileStatus.Modified)))
        val newest = listOf(FileDiff(VcsFile("newest", VcsFileStatus.Added)))
        harness.givenTurnDiff(SESSION, MessageId("m1"), older)
        harness.givenTurnDiff(SESSION, MessageId("m2"), newest)
        expectEqual(newest, harness.client.messages.turnDiff(SESSION), "newest turn")
        expectEqual(older, harness.client.messages.turnDiff(SESSION, from = MessageId("m1")), "named turn")
    }
    case("stageRevert returns what the session then carries, and changes no history") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1"), user("m2")))
        val revert = harness.client.messages.stageRevert(SESSION, MessageId("m2"))
        expectEqual(MessageId("m2"), revert.messageId, "message")
        expectEqual(revert, harness.client.sessions.get(SESSION).revert, "session")
        expectEqual(2, harness.client.messages.list(SESSION).items.size, "messages")
    }
    case("commitRevert removes the message and all after it, and clears the revert") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1"), user("m2"), user("m3")))
        harness.client.messages.stageRevert(SESSION, MessageId("m2"))
        harness.client.messages.commitRevert(SESSION)
        expectEqual(listOf("m1"), harness.client.messages.list(SESSION).items.map { it.id.value }, "messages")
        expectEqual(null, harness.client.sessions.get(SESSION).revert, "revert")
    }
    case("cancelRevert drops the staged revert and keeps the history") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenMessages(SESSION, listOf(user("m1")))
        harness.client.messages.stageRevert(SESSION, MessageId("m1"))
        harness.client.messages.cancelRevert(SESSION)
        expectEqual(null, harness.client.sessions.get(SESSION).revert, "revert")
        expectEqual(1, harness.client.messages.list(SESSION).items.size, "messages")
    }
}

/** The promises on [io.github.thewisekd.phopencode.core.api.InteractionApi]. */
fun interactionApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("forms list by session, and child forms name the child") {
        val harness = newHarness()
        harness.givenFamily()
        harness.givenForm(question("form-a", SESSION))
        harness.givenForm(question("form-b", CHILD_SESSION))
        val interactions = harness.client.interactions
        expectEqual(listOf(FormId("form-a")), interactions.listForms(SESSION).map { it.id }, "session")
        expectEqual(listOf(CHILD_SESSION), interactions.listForms(CHILD_SESSION).map { it.sessionId }, "child")
    }
    case("with no session, forms list by folder") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, directory = FOLDER.directory))
        harness.givenSession(Session(OTHER_SESSION, directory = OTHER_FOLDER.directory))
        harness.givenForm(question("form-a", SESSION))
        harness.givenForm(question("form-b", OTHER_SESSION))
        expectEqual(
            listOf(FormId("form-a")),
            harness.client.interactions.listForms(location = FOLDER).map { it.id },
            "forms",
        )
    }
    case("with neither a session nor a folder, the server's default folder is used") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, directory = harness.defaultFolder.directory))
        harness.givenSession(Session(OTHER_SESSION, directory = OTHER_FOLDER.directory))
        harness.givenForm(question("form-a", SESSION))
        harness.givenForm(question("form-b", OTHER_SESSION))
        expectEqual(listOf(FormId("form-a")), harness.client.interactions.listForms().map { it.id }, "forms")
    }
    case("with a session, the folder is not used") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, directory = FOLDER.directory))
        harness.givenForm(question("form-a", SESSION))
        expectEqual(
            listOf(FormId("form-a")),
            harness.client.interactions.listForms(SESSION, OTHER_FOLDER).map { it.id },
            "forms",
        )
    }
    case("a settled form is no longer pending") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenForm(question())
        harness.client.interactions.replyForm(SESSION, FormId("form-a"), ANSWER)
        expectEqual(emptyList(), harness.client.interactions.listForms(SESSION), "forms")
    }
    case("an invalid answer fails with BadRequest and leaves the form pending") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenForm(question())
        val failure = expectThrows<ApiException>("replyForm") {
            harness.client.interactions.replyForm(SESSION, FormId("form-a"), emptyMap())
        }
        expectThat(failure.error is ApiError.BadRequest, "Expected BadRequest, got <${failure.error}>")
        expectEqual(1, harness.client.interactions.listForms(SESSION).size, "pending forms")
    }
    case("replying to a settled form fails with Conflict") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenForm(question())
        harness.client.interactions.replyForm(SESSION, FormId("form-a"), ANSWER)
        val failure = expectThrows<ApiException>("second reply") {
            harness.client.interactions.replyForm(SESSION, FormId("form-a"), ANSWER)
        }
        expectThat(failure.error is ApiError.Conflict, "Expected Conflict, got <${failure.error}>")
    }
    case("an external field is answered with true, and an empty answer is refused") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        val external = Form(
            FormId("form-a"),
            SESSION,
            FormKind.Other(),
            fields = listOf(FormField.External(key = "login", url = "https://example.invalid/")),
        )
        harness.givenForm(external)
        val failure = expectThrows<ApiException>("empty answer") {
            harness.client.interactions.replyForm(SESSION, external.id, emptyMap())
        }
        expectThat(failure.error is ApiError.BadRequest, "Expected BadRequest, got <${failure.error}>")
        harness.client.interactions.replyForm(SESSION, external.id, mapOf("login" to DataValue.Bool(true)))
    }
    case("cancelForm declines the form") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenForm(question().copy(kind = FormKind.Other()))
        harness.client.interactions.cancelForm(SESSION, FormId("form-a"))
        expectEqual(emptyList(), harness.client.interactions.listForms(SESSION), "forms")
    }
    case("dismissing a question ends the whole execution") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenRunning(SESSION)
        harness.givenForm(question())
        val seen = connected(harness, setOf("session.execution.interrupted"))
        harness.client.interactions.cancelForm(SESSION, FormId("form-a"))
        expectEqual<ServerEvent>(
            ServerEvent.ExecutionInterrupted(SESSION, InterruptReason.Shutdown),
            seen.next("interruption"),
            "interruption",
        )
        expectEqual(emptyMap(), harness.client.sessions.activeStatuses(), "active sessions")
    }
    case("permissions list by session, and by folder without one") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION, directory = FOLDER.directory))
        harness.givenSession(Session(OTHER_SESSION, directory = OTHER_FOLDER.directory))
        harness.givenPermission(permission("permission-a", SESSION))
        harness.givenPermission(permission("permission-b", OTHER_SESSION))
        val interactions = harness.client.interactions
        expectEqual(listOf("permission-a"), interactions.listPermissions(SESSION, OTHER_FOLDER).map { it.id.value }, "session")
        expectEqual(listOf("permission-b"), interactions.listPermissions(location = OTHER_FOLDER).map { it.id.value }, "folder")
    }
    case("a replied permission is no longer pending") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenPermission(permission())
        harness.client.interactions.replyPermission(SESSION, PermissionRequestId("permission-a"), PermissionDecision.Once)
        expectEqual(emptyList(), harness.client.interactions.listPermissions(SESSION), "permissions")
    }
    case("replying twice to a permission fails with NotFound, not Conflict") {
        val harness = newHarness()
        harness.givenSession(Session(SESSION))
        harness.givenPermission(permission())
        val id = PermissionRequestId("permission-a")
        harness.client.interactions.replyPermission(SESSION, id, PermissionDecision.Reject, "no")
        expectApiError(ApiError.NotFound, "second reply") {
            harness.client.interactions.replyPermission(SESSION, id, PermissionDecision.Reject)
        }
    }
}

/** The promises on [io.github.thewisekd.phopencode.core.api.CatalogApi]. */
fun catalogApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("each list takes its folder, and null is the server's default") {
        val harness = newHarness()
        val standard = listOf(Command("default"))
        val project = listOf(Command("default"), Command("project"))
        harness.givenCommands(standard, null)
        harness.givenCommands(project, FOLDER)
        expectEqual(standard, harness.client.catalog.commands(), "default")
        expectEqual(project, harness.client.catalog.commands(FOLDER), "folder")
    }
    case("defaultModel is null when the server has no default") {
        expectEqual(null, newHarness().client.catalog.defaultModel(), "default model")
    }
}

/** The promises on [io.github.thewisekd.phopencode.core.api.VcsApi]. */
fun vcsApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    case("with no repository, no branch is checked out") {
        expectEqual(VcsBranchInfo(), newHarness().client.vcs.current(FOLDER), "branch info")
    }
    case("branches narrow by search and by limit") {
        val harness = newHarness()
        harness.givenBranches(FOLDER, listOf("main", "feature/a", "feature/b"))
        val vcs = harness.client.vcs
        expectEqual(3, vcs.branches(FOLDER).size, "all")
        expectEqual(listOf(VcsBranch("feature/a"), VcsBranch("feature/b")), vcs.branches(FOLDER, search = "feature"), "search")
        expectEqual(1, vcs.branches(FOLDER, limit = 1).size, "limit")
    }
}

/** The promises on [io.github.thewisekd.phopencode.core.api.FsApi]. */
fun fsApiContract(newHarness: suspend () -> ApiHarness): List<ContractCase> = contractCases {
    val file = FileEntry("src/main.kt", FileEntryType.File)
    val folder = FileEntry("src/main", FileEntryType.Directory)
    case("a null type finds both files and folders") {
        val harness = newHarness()
        harness.givenFiles(FOLDER, listOf(file, folder))
        expectEqual(setOf(file, folder), harness.client.fs.find(FOLDER, "main").toSet(), "entries")
    }
    case("the type narrows a search") {
        val harness = newHarness()
        harness.givenFiles(FOLDER, listOf(file, folder))
        expectEqual(listOf(file), harness.client.fs.find(FOLDER, "main", FileSearchType.File), "files")
        expectEqual(listOf(folder), harness.client.fs.find(FOLDER, "main", FileSearchType.Directory), "folders")
    }
    case("the limit caps a search, and a search covers one folder") {
        val harness = newHarness()
        harness.givenFiles(FOLDER, listOf(file, folder))
        expectEqual(1, harness.client.fs.find(FOLDER, "main", limit = 1).size, "limit")
        expectEqual(emptyList(), harness.client.fs.find(OTHER_FOLDER, "main"), "other folder")
    }
}
