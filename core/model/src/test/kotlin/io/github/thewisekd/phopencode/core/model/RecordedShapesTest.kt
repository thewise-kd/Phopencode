package io.github.thewisekd.phopencode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENTINEL = "SENTINEL-provider-text"

/** What the recordings show and the first guess at these types got wrong. See `docs/api/README.md`. */
class RecordedShapesTest {
    private val session = SessionId("ses_1")
    private val message = MessageId("msg_1")

    @Test
    fun `an error is an object, and printing it leaves out the server's text`() {
        val error = StructuredError(
            type = "provider.invalid-request",
            message = SENTINEL,
            status = 400,
            responseBody = SENTINEL,
            retryAfterMs = 1_000,
        )

        assertEquals("StructuredError(type=provider.invalid-request, status=400)", error.toString())
        assertFalse(SENTINEL in error.toString())
        assertEquals(SENTINEL, error.message)
        assertEquals(SENTINEL, error.responseBody)
    }

    @Test
    fun `errors on messages and tools are objects too`() {
        val error = StructuredError(type = "tool.execution", message = "failed")

        assertEquals(error, Message.Assistant(message, session, error = error).error)
        assertEquals(error, Message.Idle(message, session, error = error).error)
        assertEquals(error, Message.Compaction(message, session, error = error).error)
        assertEquals(error, AssistantBlock.Tool("call_1", "read", ToolStatus.Failed, error = error).error)
    }

    @Test
    fun `an idle message keeps how the execution ended`() {
        val idle = Message.Idle(message, session, outcome = SessionOutcome.Interrupted)

        assertEquals(SessionOutcome.Interrupted, idle.outcome)
    }

    @Test
    fun `a tool block is keyed by id and holds content, not a single output text`() {
        val tool = AssistantBlock.Tool(
            id = "call_1",
            name = "read",
            status = ToolStatus.Completed,
            content = listOf(
                ToolContent.Text("line"),
                ToolContent.File(uri = "file:///a.png", mime = "image/png", name = "a.png"),
                ToolContent.Unknown("hologram"),
            ),
        )

        assertEquals("call_1", tool.id)
        assertEquals(3, tool.content.size)
    }

    @Test
    fun `a tool status names the states the server has`() {
        assertEquals(ToolStatus.Streaming, AssistantBlock.Tool("c", "read", ToolStatus.Streaming).status)
    }

    @Test
    fun `an assistant message is one step, so it has no list of steps`() {
        assertFalse(Message.Assistant::class.java.declaredFields.any { it.name == "steps" })
    }

    @Test
    fun `a session keeps its outcome, its fork and its staged revert`() {
        val boundary = ForkBoundary(ForkBoundaryType.Through, message)
        val diff = FileDiff(VcsFile("a.txt", VcsFileStatus.Modified, additions = 1, deletions = 2), patch = "diff")
        val fork = SessionFork(SessionId("ses_0"), boundary)
        val revert = SessionRevert(message, snapshot = "abc", files = listOf(diff))
        val value = Session(session, outcome = SessionOutcome.Succeeded, fork = fork, revert = revert)

        assertEquals(SessionOutcome.Succeeded, value.outcome)
        assertEquals(SessionId("ses_0"), value.fork?.sessionId)
        assertEquals(ForkBoundaryType.Through, value.fork?.boundary?.type)
        assertEquals(listOf(diff), value.revert?.files)
    }

    @Test
    fun `a permission keeps the typed save list and source`() {
        val request = PermissionRequest(
            id = PermissionRequestId("per_1"),
            sessionId = session,
            action = "read",
            resources = listOf(".env"),
            save = listOf("*"),
            source = PermissionSource(type = "tool", messageId = message, id = "call_1"),
        )

        assertEquals(listOf("*"), request.save)
        assertEquals("call_1", request.source?.id)
    }

    @Test
    fun `an agent has the id the API takes apart from its display name`() {
        val agent = Agent(
            id = "build",
            name = "Build",
            mode = AgentMode.Primary,
            permissions = listOf(PermissionRule("read", "*.env", PermissionEffect.Ask)),
        )

        assertEquals("build", agent.id)
        assertEquals("Build", agent.name)
        assertEquals(PermissionEffect.Ask, agent.permissions.single().effect)
    }

    @Test
    fun `an agent switch can name the previous agent and a model switch the previous model`() {
        val model = ModelRef(providerId = "p", modelId = "m")

        assertEquals("plan", Message.AgentSwitched(message, session, agent = "build", previous = "plan").previous)
        assertEquals(model, Message.ModelSwitched(message, session, model = model, previous = model).previous)
        assertNull(Message.ModelSwitched(message, session, model = model).previous)
    }

    @Test
    fun `a compaction message keeps its summary and what it cost`() {
        val usage = TokenUsage.of(input = 5_200, output = 40, cost = Cost.parse("0.00804"))
        val compaction = Message.Compaction(message, session, reason = "manual", summary = "## Objective", usage = usage)

        assertEquals(usage, compaction.usage)
        assertTrue(compaction.summary.orEmpty().isNotEmpty())
    }
}
