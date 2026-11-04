package io.github.thewisekd.phopencode.core.testing

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.FormState
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** What the fakes promise beyond the contracts, and the knobs a test drives them with. */
class FakeApiTest {
    private val session = SessionId("session")
    private val client = FakeOpencodeClient().apply { sessions.seed(Session(session)) }

    private fun form(vararg fields: FormField) = Form(FormId("form"), session, FormKind.Other(), fields = fields.toList())

    private suspend fun assertInvalid(answers: Map<String, DataValue>) {
        val failure = assertFailsWith<ApiException> { client.interactions.replyForm(session, FormId("form"), answers) }
        assertIs<ApiError.BadRequest>(failure.error)
    }

    @Test
    fun `the server sends a next cursor on the last page too`() = runTest {
        val page = client.sessions.list(SessionQuery(limit = 10))
        assertNotNull(page.nextCursor)
        assertTrue(!page.isEnd, "A cursor alone does not end a list")
        val next = client.sessions.list(SessionQuery(limit = 10, cursor = page.nextCursor))
        assertEquals(emptyList(), next.items)
        assertTrue(next.isEnd)
    }

    @Test
    fun `a later page knows the cursor before it`() = runTest {
        client.sessions.seed(Session(SessionId("other")))
        val first = client.sessions.list(SessionQuery(limit = 1))
        assertNull(first.previousCursor)
        assertNotNull(client.sessions.list(SessionQuery(limit = 1, cursor = first.nextCursor)).previousCursor)
    }

    @Test
    fun `a cursor the server never gave is refused`() = runTest {
        val failure = assertFailsWith<ApiException> {
            client.sessions.list(SessionQuery(cursor = PageCursor("invented")))
        }
        assertIs<ApiError.BadRequest>(failure.error)
    }

    @Test
    fun `create skips an ID a seeded session already has`() = runTest {
        val seeded = Session(SessionId("ses_fake_1"), title = "seeded")
        client.sessions.seed(seeded)
        val created = client.sessions.create()
        assertTrue(created.id != seeded.id, "The new session took the seeded session's ID")
        assertEquals(seeded, client.sessions.get(seeded.id))
    }

    @Test
    fun `a generated message ID skips the IDs of seeded messages in every session`() = runTest {
        val other = SessionId("other")
        client.sessions.seed(Session(other))
        client.messages.seed(session, listOf(Message.User(MessageId("msg_fake_1"), session, text = "seeded")))
        client.messages.seed(other, listOf(Message.User(MessageId("msg_fake_2"), other, text = "other")))
        val item = client.messages.prompt(session, Prompt("new"))
        assertTrue(item.id.value !in setOf("msg_fake_1", "msg_fake_2"), "The prompt took a seeded message's ID")
        client.messages.deliver(item.id)
        val ids = client.messages.list(session).items.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Two messages share an ID: $ids")
        client.messages.stageRevert(session, item.id)
        client.messages.commitRevert(session)
        assertEquals(
            listOf("seeded"),
            client.messages.list(session).items.map { (it as Message.User).text },
            "The revert reached the wrong message",
        )
    }

    @Test
    fun `fork skips an ID a seeded session already has`() = runTest {
        val seeded = Session(SessionId("ses_fake_1"), title = "seeded")
        client.sessions.seed(seeded)
        val fork = client.sessions.fork(session)
        assertTrue(fork.id != seeded.id, "The fork took the seeded session's ID")
        assertEquals(seeded, client.sessions.get(seeded.id))
    }

    @Test
    fun `a negative cursor is refused like any unknown cursor`() = runTest {
        val failure = assertFailsWith<ApiException> {
            client.sessions.list(SessionQuery(cursor = PageCursor("fake-cursor--1")))
        }
        assertIs<ApiError.BadRequest>(failure.error)
    }

    @Test
    fun `a limit a page would refuse is refused by the branch list and the file search`() = runTest {
        val folder = Location("/srv/project")
        listOf(-1, 0).forEach { limit ->
            assertIs<ApiError.BadRequest>(
                assertFailsWith<ApiException> { client.vcs.branches(folder, limit = limit) }.error,
            )
            assertIs<ApiError.BadRequest>(
                assertFailsWith<ApiException> { client.fs.find(folder, "x", limit = limit) }.error,
            )
        }
    }

    @Test
    fun `deleting a session removes the pending items of its whole family`() = runTest {
        val child = SessionId("child")
        val grandchild = SessionId("grandchild")
        val other = SessionId("other")
        client.sessions.seed(Session(child, parentId = session))
        client.sessions.seed(Session(grandchild, parentId = child))
        client.sessions.seed(Session(other))
        val parentForm = Form(FormId("parent"), session, FormKind.Other())
        val childForm = Form(FormId("child"), grandchild, FormKind.Other())
        val otherForm = Form(FormId("other"), other, FormKind.Other())
        listOf(parentForm, childForm, otherForm).forEach(client.interactions::seedForm)
        val request = PermissionRequest(PermissionRequestId("child"), grandchild, "read")
        val otherRequest = PermissionRequest(PermissionRequestId("other"), other, "read")
        client.interactions.seedPermission(request)
        client.interactions.seedPermission(otherRequest)

        client.sessions.delete(session)

        assertEquals(emptyList(), client.interactions.listForms(grandchild))
        assertEquals(emptyList(), client.interactions.listPermissions(grandchild))
        assertNull(client.interactions.stateOf(parentForm.id))
        assertEquals(
            ApiError.NotFound,
            assertFailsWith<ApiException> { client.interactions.replyForm(session, parentForm.id, emptyMap()) }.error,
        )
        assertEquals(
            ApiError.NotFound,
            assertFailsWith<ApiException> {
                client.interactions.replyPermission(grandchild, request.id, PermissionDecision.Once)
            }.error,
        )
        assertEquals(listOf(otherForm), client.interactions.listForms(other))
        assertEquals(listOf(otherRequest), client.interactions.listPermissions(other))
    }

    @Test
    fun `a deleted session leaves no turn diff for a later one with the same ID`() = runTest {
        val message = Message.User(MessageId("m"), session, text = "x")
        client.messages.seed(session, listOf(message))
        client.messages.setTurnDiff(session, message.id, listOf(FileDiff(VcsFile("a", VcsFileStatus.Added))))
        client.sessions.delete(session)
        client.sessions.seed(Session(session))
        client.messages.seed(session, listOf(message))
        assertEquals(emptyList(), client.messages.turnDiff(session))
    }

    @Test
    fun `a call waiting for the state lock fails with Closed if the client closes meanwhile`() {
        var outcome: Result<Session>? = null
        val caller = thread(start = false) {
            outcome = runCatching { runBlocking { client.sessions.get(session) } }
        }
        client.sessions.withLock {
            caller.start()
            caller.awaitBlockedOrDone()
            client.close()
        }
        caller.join()
        assertEquals(ApiError.Closed, assertIs<ApiException>(outcome?.exceptionOrNull()).error)
    }

    @Test
    fun `a test can still arrange and inspect the state after close`() = runTest {
        client.close()
        client.sessions.seed(Session(SessionId("later")))
        client.messages.seed(session, listOf(Message.User(MessageId("m"), session, text = "x")))
        client.interactions.seedForm(form())
        assertEquals<FormState?>(FormState.Pending, client.interactions.stateOf(FormId("form")))
        assertEquals(emptyList(), client.messages.commandsRun)
    }

    @Test
    fun `failures repeat as many times as asked, in order`() = runTest {
        client.control.failNext(ApiError.Timeout, times = 2)
        client.control.failNext(ApiError.Network)
        val seen = List(3) { assertFailsWith<ApiException> { client.sessions.list() }.error }
        assertEquals(listOf(ApiError.Timeout, ApiError.Timeout, ApiError.Network), seen)
        client.sessions.list()
    }

    @Test
    fun `a stalled call runs on after resume`() = runTest {
        client.control.stall()
        val call = async { client.sessions.get(session) }
        client.control.awaitRunningCalls(1)
        client.control.resume()
        assertEquals(session, call.await().id)
    }

    @Test
    fun `calls are recorded by name`() = runTest {
        client.sessions.list()
        client.catalog.skills()
        assertEquals(listOf("sessions.list", "catalog.skills"), client.control.calls)
    }

    @Test
    fun `a version the fake cannot read is accepted with isVersionUnknown`() = runTest {
        listOf("", "local", "dev", "v", "not a version").forEach { raw ->
            client.serverVersion = raw
            val info = client.serverInfo()
            assertTrue(info.isVersionUnknown, "<$raw> is an unknown version")
            assertTrue(!info.isNewerMajor)
            assertEquals(raw, info.version)
        }
        client.serverVersion = "x".repeat(10_000)
        assertEquals("x".repeat(200), client.serverInfo().version)
        client.serverVersion = "2.2.0"
        assertTrue(!client.serverInfo().isVersionUnknown)
    }

    @Test
    fun `a newer major version is a warning, not an error`() = runTest {
        client.serverVersion = "3.0.0"
        assertTrue(client.serverInfo().isNewerMajor)
        client.serverVersion = "2.2.5"
        assertTrue(!client.serverInfo().isNewerMajor)
    }

    @Test
    fun `an inbox item becomes a message with its ID once delivered`() = runTest {
        val item = client.messages.prompt(session, Prompt("hello", files = listOf("a.txt")))
        assertEquals(emptyList(), client.messages.list(session).items)
        client.messages.deliver(item.id)
        val delivered = assertIs<Message.User>(client.messages.list(session).items.single())
        assertEquals(item.id, delivered.id)
        assertEquals(listOf("a.txt"), delivered.files)
    }

    @Test
    fun `state changes send their events`() = runTest {
        client.events.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())

            val item = client.messages.prompt(session, Prompt("hello"))
            val enqueued = assertIs<ServerEvent.InboxEnqueued>(awaitItem())
            assertNull(enqueued.item.createdAt, "The event's item has no time")
            client.messages.deliver(item.id)
            assertEquals<ServerEvent>(ServerEvent.InboxDelivered(session, item.id), awaitItem())

            client.sessions.update(session, SessionUpdate(title = "new"))
            assertEquals<ServerEvent>(ServerEvent.SessionRenamed(session, "new"), awaitItem())
            client.sessions.update(session, SessionUpdate(archived = true))
            assertEquals<ServerEvent>(ServerEvent.SessionArchived(session), awaitItem())

            client.messages.stageRevert(session, item.id)
            assertIs<ServerEvent.RevertStaged>(awaitItem())
            client.messages.cancelRevert(session)
            assertEquals<ServerEvent>(ServerEvent.RevertCleared(session), awaitItem())
            assertEquals<ServerEvent>(ServerEvent.ExecutionStarted(session), awaitItem())
            assertEquals<ServerEvent>(ServerEvent.ExecutionSucceeded(session), awaitItem())

            client.messages.stageRevert(session, item.id)
            assertIs<ServerEvent.RevertStaged>(awaitItem())
            client.messages.commitRevert(session)
            assertEquals<ServerEvent>(ServerEvent.RevertCommitted(session, item.id), awaitItem())

            client.sessions.delete(session)
            assertEquals<ServerEvent>(ServerEvent.SessionDeleted(session), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seeding sends no event`() = runTest {
        client.events.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            client.sessions.seed(Session(SessionId("seeded")))
            client.interactions.seedForm(form())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reverting needs a user message, and only a staged revert can end`() = runTest {
        val assistant = Message.Assistant(MessageId("assistant"), session)
        client.messages.seed(session, listOf(assistant))
        assertIs<ApiError.BadRequest>(
            assertFailsWith<ApiException> { client.messages.stageRevert(session, assistant.id) }.error,
        )
        assertEquals(
            ApiError.NotFound,
            assertFailsWith<ApiException> { client.messages.stageRevert(session, MessageId("missing")) }.error,
        )
        assertIs<ApiError.Conflict>(assertFailsWith<ApiException> { client.messages.commitRevert(session) }.error)
        assertIs<ApiError.Conflict>(assertFailsWith<ApiException> { client.messages.cancelRevert(session) }.error)
    }

    @Test
    fun `a forked copy belongs to the new session`() = runTest {
        client.messages.seed(session, listOf(Message.User(MessageId("m"), session, text = "x")))
        val fork = client.sessions.fork(session)
        assertEquals(fork.id, client.messages.list(fork.id).items.single().sessionId)
        assertEquals(session, fork.fork?.sessionId)
    }

    @Test
    fun `a question field refuses a value that is not offered, unless custom answers are allowed`() = runTest {
        val options = listOf(FormOption("a", "A"))
        client.interactions.seedForm(form(FormField.SingleChoice(key = "q0", options = options)))
        assertInvalid(mapOf("q0" to DataValue.Text("z")))
        client.interactions.replyForm(session, FormId("form"), mapOf("q0" to DataValue.Text("a")))

        client.interactions.seedForm(
            Form(FormId("custom"), session, FormKind.Question, fields = listOf(FormField.SingleChoice(key = "q0", options = options, allowCustom = true))),
        )
        client.interactions.replyForm(session, FormId("custom"), mapOf("q0" to DataValue.Text("z")))
    }

    @Test
    fun `a multiple choice checks its limits`() = runTest {
        val options = listOf(FormOption("a", "A"), FormOption("b", "B"))
        client.interactions.seedForm(
            form(FormField.MultipleChoice(key = "q0", options = options, minSelected = 1, maxSelected = 1)),
        )
        assertInvalid(mapOf("q0" to DataValue.Items(emptyList())))
        assertInvalid(mapOf("q0" to DataValue.Items(listOf(DataValue.Text("a"), DataValue.Text("b")))))
        client.interactions.replyForm(session, FormId("form"), mapOf("q0" to DataValue.Items(listOf(DataValue.Text("a")))))
    }

    @Test
    fun `an answer for a field the form does not have is invalid`() = runTest {
        client.interactions.seedForm(form(FormField.YesNo(key = "q0")))
        assertInvalid(mapOf("other" to DataValue.Bool(true)))
    }

    @Test
    fun `an answer of the wrong type is invalid`() = runTest {
        client.interactions.seedForm(form(FormField.YesNo(key = "q0")))
        assertInvalid(mapOf("q0" to DataValue.Text("yes")))
    }

    @Test
    fun `a field hidden by its condition is not required`() = runTest {
        val condition = io.github.thewisekd.phopencode.core.model.FieldCondition.Equals("q0", DataValue.Bool(true))
        client.interactions.seedForm(
            form(
                FormField.YesNo(key = "q0"),
                FormField.FreeText(key = "q1", required = true, visibleWhen = listOf(condition)),
            ),
        )
        assertInvalid(mapOf("q0" to DataValue.Bool(true)))
        client.interactions.replyForm(session, FormId("form"), mapOf("q0" to DataValue.Bool(false)))
    }

    @Test
    fun `an invalid answer does not echo in the error`() = runTest {
        client.interactions.seedForm(form(FormField.YesNo(key = "q0")))
        val failure = assertFailsWith<ApiException> {
            client.interactions.replyForm(session, FormId("form"), mapOf("q0" to DataValue.Text("hunter2")))
        }
        assert("hunter2" !in assertIs<ApiError.BadRequest>(failure.error).detail.orEmpty())
    }

    @Test
    fun `the settled state and the answer can be read back`() = runTest {
        client.interactions.seedForm(form(FormField.YesNo(key = "q0")))
        val answer = mapOf("q0" to DataValue.Bool(true))
        client.interactions.replyForm(session, FormId("form"), answer)
        assertEquals<FormState?>(FormState.Answered(answer), client.interactions.stateOf(FormId("form")))
    }

    @Test
    fun `a form's own session must be named`() = runTest {
        client.interactions.seedForm(form())
        assertEquals(
            ApiError.NotFound,
            assertFailsWith<ApiException> {
                client.interactions.replyForm(SessionId("other"), FormId("form"), emptyMap())
            }.error,
        )
    }

    @Test
    fun `a cancelled form cannot be cancelled again`() = runTest {
        client.interactions.seedForm(form())
        client.interactions.cancelForm(session, FormId("form"))
        assertIs<ApiError.Conflict>(
            assertFailsWith<ApiException> { client.interactions.cancelForm(session, FormId("form")) }.error,
        )
    }

    @Test
    fun `the always decision saves a rule at the request's scope`() = runTest {
        val request = PermissionRequest(PermissionRequestId("p"), session, "read", save = listOf("*"))
        client.interactions.seedPermission(request)
        client.interactions.replyPermission(session, request.id, PermissionDecision.Always)
        assertEquals(listOf(PermissionRule("read", "*", PermissionEffect.Allow)), client.interactions.savedRules)
    }

    @Test
    fun `a rejection's message is recorded`() = runTest {
        val request = PermissionRequest(PermissionRequestId("p"), session, "read")
        client.interactions.seedPermission(request)
        client.interactions.replyPermission(session, request.id, PermissionDecision.Reject, "not now")
        assertEquals("not now", client.interactions.permissionReplies.single().message)
    }

    @Test
    fun `a folder with no list of its own sees the default one`() = runTest {
        val folder = Location("/srv/other")
        client.catalog.setCommands(listOf(io.github.thewisekd.phopencode.core.model.Command("default")), null)
        assertEquals(1, client.catalog.commands(folder).size)
    }

    @Test
    fun `compact is accepted, and the test sends the result`() = runTest {
        val item = client.sessions.compact(session)
        assertEquals<InboxContent>(InboxContent.Compaction, item.content)
    }
}
