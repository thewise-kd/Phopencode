package io.github.thewisekd.phopencode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V2 changes fast. Each `when` here has no `else`, so a new variant fails compilation, and each
 * unknown variant keeps the raw value the server sent.
 */
class UnknownStatesTest {
    private fun describe(block: AssistantBlock): String = when (block) {
        is AssistantBlock.Text -> "text:${block.text}"
        is AssistantBlock.Reasoning -> "reasoning:${block.text}"
        is AssistantBlock.Tool -> "tool:${block.name}"
        is AssistantBlock.Unknown -> "unknown:${block.rawType}"
    }

    private fun describe(status: ToolStatus): String = when (status) {
        ToolStatus.Streaming -> "streaming"
        ToolStatus.Running -> "running"
        ToolStatus.Completed -> "completed"
        ToolStatus.Failed -> "failed"
        is ToolStatus.Unknown -> "unknown:${status.raw}"
    }

    private fun describe(message: Message): String = when (message) {
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
        is Message.Synthetic -> "synthetic"
        is Message.System -> "system"
        is Message.SkillInvoked -> "skill"
        is Message.Shell -> "shell"
        is Message.Compaction -> "compaction"
        is Message.Idle -> "idle"
        is Message.AgentSwitched -> "agent-switched"
        is Message.ModelSwitched -> "model-switched"
        is Message.LocationSwitched -> "location-switched"
        is Message.Unknown -> "unknown:${message.rawType}"
    }

    private fun describe(field: FormField): String = when (field) {
        is FormField.SingleChoice -> "single"
        is FormField.MultipleChoice -> "multiple"
        is FormField.FreeText -> "text"
        is FormField.Number -> "number"
        is FormField.YesNo -> "yes-no"
        is FormField.External -> "external"
        is FormField.Unknown -> "unknown:${field.rawType}"
    }

    @Test
    fun `blocks are told apart, and an unknown block keeps its type`() {
        val blocks = listOf(
            AssistantBlock.Text(text = "answer"),
            AssistantBlock.Reasoning(text = "thinking"),
            AssistantBlock.Tool(id = "call-1", name = "shell", status = ToolStatus.Running),
            AssistantBlock.Unknown(rawType = "hologram"),
        )

        assertEquals(listOf("text:answer", "reasoning:thinking", "tool:shell", "unknown:hologram"), blocks.map(::describe))
    }

    @Test
    fun `reasoning text can be empty, because providers may hide it`() {
        assertEquals("reasoning:", describe(AssistantBlock.Reasoning(text = "")))
    }

    @Test
    fun `an unknown tool status keeps the raw value`() {
        assertEquals("unknown:paused", describe(ToolStatus.Unknown("paused")))
        assertEquals("completed", describe(ToolStatus.Completed))
    }

    @Test
    fun `a tool can name an unknown tool, carry free-form input and link a subagent session`() {
        val tool = AssistantBlock.Tool(
            id = "call-1",
            name = "subagent",
            status = ToolStatus.Unknown("paused"),
            input = DataValue.Fields(mapOf("task" to DataValue.Text("look around"))),
            childSessionId = SessionId("child-1"),
        )

        assertEquals(SessionId("child-1"), tool.childSessionId)
        assertEquals("tool:subagent", describe(tool))
    }

    @Test
    fun `an unknown message type keeps its raw type`() {
        val message = Message.Unknown(id = MessageId("m1"), sessionId = SessionId("s1"), rawType = "teleported")

        assertEquals("unknown:teleported", describe(message))
    }

    @Test
    fun `an unknown form field keeps its raw type, key and required flag`() {
        val field = FormField.Unknown(key = "q0", title = "Where?", required = true, rawType = "signature")

        assertEquals("unknown:signature", describe(field))
        assertEquals("q0", field.key)
        assertEquals(true, field.required)
    }

    @Test
    fun `the remaining open types each have an unknown case`() {
        val raw: (Any) -> String = { value ->
            when (value) {
                is VcsFileStatus.Unknown -> value.raw
                is FileEntryType.Unknown -> value.raw
                is AgentMode.Unknown -> value.raw
                is SessionStatus.Unknown -> value.raw
                is SessionActivity.Unknown -> value.raw
                is SessionOutcome.Unknown -> value.raw
                is ForkBoundaryType.Unknown -> value.raw
                is FormState.Unknown -> value.raw
                is TextFormat.Unknown -> value.raw
                is PermissionEffect.Unknown -> value.raw
                is FieldCondition.Unknown -> value.raw
                else -> error("not an unknown case: $value")
            }
        }

        listOf(
            VcsFileStatus.Unknown("x"),
            FileEntryType.Unknown("x"),
            AgentMode.Unknown("x"),
            SessionStatus.Unknown("x"),
            SessionActivity.Unknown("x"),
            SessionOutcome.Unknown("x"),
            ForkBoundaryType.Unknown("x"),
            FormState.Unknown("x"),
            TextFormat.Unknown("x"),
            PermissionEffect.Unknown("x"),
            FieldCondition.Unknown("x"),
        ).forEach { assertEquals("x", raw(it)) }
    }
}
