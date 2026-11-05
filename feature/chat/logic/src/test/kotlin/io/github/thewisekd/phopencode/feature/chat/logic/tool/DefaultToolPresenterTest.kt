package io.github.thewisekd.phopencode.feature.chat.logic.tool

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultToolPresenterTest {
    private val sessionId = SessionId("ses_1")
    private val msg1 = MessageId("msg_1")
    private val msg2 = MessageId("msg_2")

    private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
        val mutable = MutableStateFlow(ChatSessionState())
        override val state: StateFlow<ChatSessionState> = mutable.asStateFlow()

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T = throw ApiException(ApiError.Closed)
    }

    private fun FakeChatSession.show(vararg messages: Message) {
        mutable.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(session = Session(id = sessionId), messages = messages.toList()),
        )
    }

    private fun assistant(id: MessageId, vararg blocks: AssistantBlock) =
        Message.Assistant(id = id, sessionId = sessionId, blocks = blocks.toList())

    private fun text(value: String) = DataValue.Text(value)

    private fun num(value: Int) = DataValue.Num(BigDecimal(value))

    private fun fields(vararg pairs: Pair<String, DataValue>) = DataValue.Fields(mapOf(*pairs))

    private fun tool(
        id: String,
        name: String,
        input: DataValue? = null,
        status: ToolStatus = ToolStatus.Completed,
        content: List<ToolContent> = emptyList(),
        error: StructuredError? = null,
        metadata: Map<String, DataValue> = emptyMap(),
        childSessionId: SessionId? = null,
    ) = AssistantBlock.Tool(
        id = id,
        name = name,
        status = status,
        input = input,
        content = content,
        error = error,
        metadata = metadata,
        childSessionId = childSessionId,
    )

    private fun TestScope.presenter(): Pair<FakeChatSession, DefaultToolPresenter> {
        val session = FakeChatSession(sessionId)
        return session to DefaultToolPresenter(session = session, scope = backgroundScope)
    }

    private fun TestScope.cardFor(block: AssistantBlock.Tool): ToolCardState {
        val (session, presenter) = presenter()
        session.show(assistant(msg1, block))
        runCurrent()
        return presenter.state.value.cards.getValue(TimelineKey.tool(msg1, block.id))
    }

    @Test
    fun `nothing runs in the constructor`() = runTest {
        val session = FakeChatSession(sessionId)
        session.show(assistant(msg1, tool("c1", "shell", fields("command" to text("ls")))))
        val presenter = DefaultToolPresenter(session = session, scope = backgroundScope)

        assertEquals(ToolState(), presenter.state.value)
        runCurrent()
        assertEquals(setOf(TimelineKey.tool(msg1, "c1")), presenter.state.value.cards.keys)
    }

    @Test
    fun `each tool block gets a card keyed by message and call id`() = runTest {
        val (session, presenter) = presenter()
        session.show(
            assistant(
                msg1,
                AssistantBlock.Text("hello"),
                tool("c1", "shell", fields("command" to text("ls"))),
                AssistantBlock.Reasoning("thinking"),
                tool("c2", "read", fields("path" to text("a.txt"))),
            ),
            Message.User(id = MessageId("u1"), sessionId = sessionId, text = "hi"),
            assistant(msg2, tool("c3", "glob", fields("pattern" to text("*.kt")))),
        )
        runCurrent()

        assertEquals(
            listOf(TimelineKey.tool(msg1, "c1"), TimelineKey.tool(msg1, "c2"), TimelineKey.tool(msg2, "c3")),
            presenter.state.value.cards.keys.toList(),
        )
        val card = presenter.state.value.cards.getValue(TimelineKey.tool(msg1, "c2"))
        assertEquals("c2", card.callId)
        assertEquals("read", card.name)
    }

    @Test
    fun `the shell tool shows its command`() = runTest {
        val card = cardFor(tool("c1", "shell", fields("command" to text("printf 'one\\ntwo\\n'"))))

        assertEquals(ToolSummary.Shell("printf 'one\\ntwo\\n'"), card.summary)
    }

    @Test
    fun `the v1 bash tool is a shell too`() = runTest {
        val card = cardFor(tool("c1", "bash", fields("command" to text("ls -la"), "description" to text("list"))))

        assertEquals(ToolSummary.Shell("ls -la"), card.summary)
    }

    @Test
    fun `the edit tool shows its path and the reported line counts`() = runTest {
        val card = cardFor(
            tool(
                "c1",
                "edit",
                fields("path" to text("tracked.txt"), "oldString" to text("alpha"), "newString" to text("beta")),
                metadata = mapOf(
                    "files" to DataValue.Items(
                        listOf(
                            fields(
                                "file" to text("tracked.txt"),
                                "patch" to text("diff"),
                                "status" to text("modified"),
                                "additions" to num(3),
                                "deletions" to num(1),
                            ),
                        ),
                    ),
                    "truncated" to DataValue.Bool(false),
                ),
            ),
        )

        assertEquals(ToolSummary.Edit("tracked.txt", additions = 3, deletions = 1), card.summary)
    }

    @Test
    fun `an edit without reported counts has null counts`() = runTest {
        val card = cardFor(tool("c1", "edit", fields("path" to text("a.kt")), status = ToolStatus.Running))

        assertEquals(ToolSummary.Edit("a.kt"), card.summary)
    }

    @Test
    fun `the v1 edit reads filePath and filediff`() = runTest {
        val card = cardFor(
            tool(
                "c1",
                "edit",
                fields("filePath" to text("/srv/p/a.kt")),
                metadata = mapOf("filediff" to fields("additions" to num(2), "deletions" to num(0))),
            ),
        )

        assertEquals(ToolSummary.Edit("/srv/p/a.kt", additions = 2, deletions = 0), card.summary)
    }

    @Test
    fun `invalid line counts are dropped`() = runTest {
        val card = cardFor(
            tool(
                "c1",
                "edit",
                fields("path" to text("a.kt")),
                metadata = mapOf(
                    "files" to DataValue.Items(
                        listOf(fields("file" to text("a.kt"), "additions" to num(-1), "deletions" to text("2"))),
                    ),
                ),
            ),
        )

        assertEquals(ToolSummary.Edit("a.kt"), card.summary)
    }

    @Test
    fun `the write tool is an edit of its path`() = runTest {
        val card = cardFor(tool("c1", "write", fields("path" to text("notes.txt"), "content" to text("secret"))))

        assertEquals(ToolSummary.Edit("notes.txt"), card.summary)
    }

    @Test
    fun `the read tool shows its path`() = runTest {
        assertEquals(ToolSummary.Read("notes.txt"), cardFor(tool("c1", "read", fields("path" to text("notes.txt")))).summary)
        assertEquals(
            ToolSummary.Read("/srv/p/notes.txt"),
            cardFor(tool("c2", "read", fields("filePath" to text("/srv/p/notes.txt"), "offset" to num(10)))).summary,
        )
    }

    @Test
    fun `grep and glob show their pattern and where they searched`() = runTest {
        assertEquals(
            ToolSummary.Search("TODO", path = "src"),
            cardFor(tool("c1", "grep", fields("pattern" to text("TODO"), "path" to text("src")))).summary,
        )
        assertEquals(
            ToolSummary.Search("**/*.kt"),
            cardFor(tool("c2", "glob", fields("pattern" to text("**/*.kt")))).summary,
        )
    }

    @Test
    fun `an unknown tool gets the generic summary`() = runTest {
        val card = cardFor(tool("c1", "websearch", fields("query" to text("kotlin"))))

        assertEquals(ToolSummary.Generic("websearch"), card.summary)
        assertEquals(ToolCardStatus.Completed, card.status)
    }

    @Test
    fun `a known tool without its main input falls back to the generic summary`() = runTest {
        assertEquals(ToolSummary.Generic("shell"), cardFor(tool("c1", "shell")).summary)
        assertEquals(ToolSummary.Generic("read"), cardFor(tool("c2", "read", fields("path" to num(1)))).summary)
        assertEquals(ToolSummary.Generic("grep"), cardFor(tool("c3", "grep", fields("pattern" to text("  ")))).summary)
    }

    @Test
    fun `a call whose input is still streaming is preparing and shows the raw text`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.tool(msg1, "c1")
        session.show(assistant(msg1, tool("c1", "shell", text("{\"comm"), status = ToolStatus.Streaming)))
        runCurrent()

        val preparing = presenter.state.value.cards.getValue(key)
        assertEquals(ToolCardStatus.Preparing, preparing.status)
        assertEquals("{\"comm", preparing.input)
        assertEquals(ToolSummary.Generic("shell"), preparing.summary)

        session.show(assistant(msg1, tool("c1", "shell", fields("command" to text("ls")), status = ToolStatus.Running)))
        runCurrent()

        val running = presenter.state.value.cards.getValue(key)
        assertEquals(ToolCardStatus.Running, running.status)
        assertEquals(ToolSummary.Shell("ls"), running.summary)
    }

    @Test
    fun `statuses map one to one`() = runTest {
        assertEquals(ToolCardStatus.Running, cardFor(tool("a", "x", status = ToolStatus.Running)).status)
        assertEquals(ToolCardStatus.Completed, cardFor(tool("b", "x", status = ToolStatus.Completed)).status)
        assertEquals(ToolCardStatus.Failed, cardFor(tool("c", "x", status = ToolStatus.Failed)).status)
        assertEquals(ToolCardStatus.Unknown, cardFor(tool("d", "x", status = ToolStatus.Unknown("queued"))).status)
    }

    @Test
    fun `a failed call shows the error state and keeps its summary`() = runTest {
        val error = StructuredError(type = "tool.execution", message = "File not found: missing.txt")
        val card = cardFor(
            tool("c1", "read", fields("path" to text("missing.txt")), status = ToolStatus.Failed, error = error),
        )

        assertEquals(ToolCardStatus.Failed, card.status)
        assertEquals(error, card.error)
        assertEquals(ToolSummary.Read("missing.txt"), card.summary)
        assertNull(card.output)
    }

    @Test
    fun `parsed input is formatted as indented JSON`() = runTest {
        val card = cardFor(
            tool(
                "c1",
                "mytool",
                fields(
                    "path" to text("a \"b\"\n"),
                    "count" to DataValue.Num(BigDecimal("1.50")),
                    "flag" to DataValue.Bool(true),
                    "none" to DataValue.Null,
                    "list" to DataValue.Items(listOf(num(1), text("x"))),
                    "empty" to DataValue.Fields(emptyMap()),
                    "nothing" to DataValue.Items(emptyList()),
                ),
            ),
        )

        assertEquals(
            """
            {
              "path": "a \"b\"\n",
              "count": 1.50,
              "flag": true,
              "none": null,
              "list": [
                1,
                "x"
              ],
              "empty": {},
              "nothing": []
            }
            """.trimIndent(),
            card.input,
        )
    }

    @Test
    fun `control characters in parsed input are escaped`() = runTest {
        val card = cardFor(tool("c1", "mytool", fields("k\t" to text("a\u0001b\\"))))

        assertEquals("{\n  \"k\\t\": \"a\\u0001b\\\\\"\n}", card.input)
    }

    @Test
    fun `no input gives null input`() = runTest {
        assertNull(cardFor(tool("c1", "shell")).input)
    }

    @Test
    fun `output joins the text parts and is null before any`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.tool(msg1, "c1")
        session.show(assistant(msg1, tool("c1", "shell", status = ToolStatus.Running)))
        runCurrent()
        assertNull(presenter.state.value.cards.getValue(key).output)

        session.show(
            assistant(
                msg1,
                tool(
                    "c1",
                    "shell",
                    content = listOf(
                        ToolContent.Text("one"),
                        ToolContent.File(uri = "file:///a.png", mime = "image/png"),
                        ToolContent.Unknown("blob"),
                        ToolContent.Text("two"),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals("one\ntwo", presenter.state.value.cards.getValue(key).output)
    }

    @Test
    fun `only file parts give null output`() = runTest {
        val card = cardFor(tool("c1", "x", content = listOf(ToolContent.File(uri = "file:///a", mime = "text/plain"))))

        assertNull(card.output)
    }

    @Test
    fun `a subagent call carries its child session`() = runTest {
        val child = SessionId("ses_child")
        val card = cardFor(tool("c1", "subagent", fields("agent" to text("general")), childSessionId = child))

        assertEquals(child, card.childSessionId)
        assertEquals(ToolSummary.Generic("subagent"), card.summary)
    }

    @Test
    fun `toggle expands each section separately and survives updates`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.tool(msg1, "c1")
        val other = TimelineKey.tool(msg1, "c2")
        session.show(
            assistant(
                msg1,
                tool("c1", "shell", fields("command" to text("ls")), status = ToolStatus.Running),
                tool("c2", "read", fields("path" to text("a"))),
            ),
        )
        runCurrent()
        assertEquals(emptySet(), presenter.state.value.cards.getValue(key).expanded)

        presenter.onIntent(ToolIntent.Toggle(key, ToolSection.Output))
        runCurrent()
        assertEquals(setOf(ToolSection.Output), presenter.state.value.cards.getValue(key).expanded)
        assertEquals(emptySet(), presenter.state.value.cards.getValue(other).expanded)

        presenter.onIntent(ToolIntent.Toggle(key, ToolSection.Input))
        runCurrent()
        assertEquals(setOf(ToolSection.Input, ToolSection.Output), presenter.state.value.cards.getValue(key).expanded)

        session.show(
            assistant(
                msg1,
                tool("c1", "shell", fields("command" to text("ls")), content = listOf(ToolContent.Text("a"))),
                tool("c2", "read", fields("path" to text("a"))),
            ),
        )
        runCurrent()
        val updated = presenter.state.value.cards.getValue(key)
        assertEquals(ToolCardStatus.Completed, updated.status)
        assertEquals(setOf(ToolSection.Input, ToolSection.Output), updated.expanded)

        presenter.onIntent(ToolIntent.Toggle(key, ToolSection.Output))
        runCurrent()
        assertEquals(setOf(ToolSection.Input), presenter.state.value.cards.getValue(key).expanded)
    }

    @Test
    fun `the expanded state survives the session going away and coming back`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.tool(msg1, "c1")
        session.show(assistant(msg1, tool("c1", "shell", fields("command" to text("ls")))))
        runCurrent()
        presenter.onIntent(ToolIntent.Toggle(key, ToolSection.Input))
        runCurrent()

        session.mutable.value = ChatSessionState(availability = ChatAvailability.NotConnected, session = null)
        runCurrent()
        assertTrue(presenter.state.value.cards.isEmpty())

        session.show(assistant(msg1, tool("c1", "shell", fields("command" to text("ls")))))
        runCurrent()
        assertEquals(setOf(ToolSection.Input), presenter.state.value.cards.getValue(key).expanded)
    }

    @Test
    fun `toggling a key with no card does nothing`() = runTest {
        val (session, presenter) = presenter()
        session.show(assistant(msg1, tool("c1", "shell", fields("command" to text("ls")))))
        runCurrent()
        val before = presenter.state.value

        presenter.onIntent(ToolIntent.Toggle(TimelineKey.tool(msg2, "c1"), ToolSection.Input))
        runCurrent()

        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `printing the state masks inputs, outputs, commands and patterns`() = runTest {
        val (session, presenter) = presenter()
        session.show(
            assistant(
                msg1,
                tool(
                    "c1",
                    "shell",
                    fields("command" to text("export TOKEN=hunter2")),
                    content = listOf(ToolContent.Text("output-secret")),
                ),
                tool("c2", "grep", fields("pattern" to text("password=swordfish"))),
                tool("c3", "write", fields("path" to text("a.txt"), "content" to text("file-secret"))),
            ),
        )
        runCurrent()

        val printed = presenter.state.value.toString()
        for (secret in listOf("hunter2", "output-secret", "swordfish", "file-secret")) {
            assertFalse(printed.contains(secret), "printed $secret")
        }
    }
}
