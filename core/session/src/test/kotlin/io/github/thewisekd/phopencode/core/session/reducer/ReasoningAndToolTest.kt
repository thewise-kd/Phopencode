package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningAndToolTest {
    private val script = EventScript()
    private val reducer = SessionReducer(SESSION)

    private fun reasoning() = reducer.assistant(MSG_A).blocks.filterIsInstance<AssistantBlock.Reasoning>().single()

    @Test
    fun `reasoning streams, then takes its times and final text from started and ended`() {
        val started = script.durable()
        reducer.apply(ServerEvent.ReasoningStarted(SESSION, MSG_A, 0, started))
        reducer.apply(ServerEvent.ReasoningDelta(SESSION, MSG_A, 0, "Weighing", script.live()))

        assertEquals("Weighing", reasoning().text)
        assertEquals(started.createdAt, reasoning().createdAt)
        assertNull(reasoning().completedAt)

        val ended = script.durable()
        reducer.apply(ServerEvent.ReasoningEnded(SESSION, MSG_A, 0, "Weighing the options.", ended))

        assertEquals("Weighing the options.", reasoning().text)
        assertEquals(ended.createdAt, reasoning().completedAt)
    }

    @Test
    fun `empty reasoning is kept as an empty block`() {
        reducer.apply(ServerEvent.ReasoningStarted(SESSION, MSG_A, 0, script.durable()))
        reducer.apply(ServerEvent.ReasoningEnded(SESSION, MSG_A, 0, "", script.durable()))

        assertEquals("", reasoning().text)
    }

    @Test
    fun `a tool goes from streaming input to running to completed`() {
        val inputStarted = script.durable()
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", inputStarted))

        reducer.tool(MSG_A, "call_1").let {
            assertEquals("read", it.name)
            assertEquals(ToolStatus.Streaming, it.status)
            assertEquals(inputStarted.createdAt, it.createdAt)
        }

        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "{\"path\":", script.live()))
        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "\"a.txt\"}", script.live()))
        assertEquals(DataValue.Text("{\"path\":\"a.txt\"}"), reducer.tool(MSG_A, "call_1").input)

        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\": \"a.txt\"}", script.durable()))
        assertEquals(DataValue.Text("{\"path\": \"a.txt\"}"), reducer.tool(MSG_A, "call_1").input)

        val called = script.durable()
        val parsed = DataValue.Fields(mapOf("path" to DataValue.Text("a.txt")))
        reducer.apply(ServerEvent.ToolCalled(SESSION, MSG_A, "call_1", parsed, executed = false, meta = called))
        reducer.tool(MSG_A, "call_1").let {
            assertEquals(ToolStatus.Running, it.status)
            assertEquals(parsed, it.input)
            assertEquals(called.createdAt, it.startedAt)
            assertEquals(false, it.executed)
        }

        val child = SessionId("ses_child")
        val progressMeta = mapOf("sessionID" to DataValue.Text(child.value), "status" to DataValue.Text("running"))
        reducer.apply(ServerEvent.ToolProgress(SESSION, MSG_A, "call_1", child, "running", progressMeta, script.live()))
        reducer.tool(MSG_A, "call_1").let {
            assertEquals(child, it.childSessionId)
            assertEquals(progressMeta, it.metadata)
        }

        val succeeded = script.durable()
        val finalMeta = mapOf("truncated" to DataValue.Bool(false))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "call_1", listOf(ToolContent.Text("ok")), finalMeta, false, succeeded))
        reducer.tool(MSG_A, "call_1").let {
            assertEquals(ToolStatus.Completed, it.status)
            assertEquals(listOf(ToolContent.Text("ok")), it.content)
            assertEquals(finalMeta, it.metadata)
            assertEquals(succeeded.createdAt, it.completedAt)
            assertEquals(parsed, it.input)
            assertEquals(child, it.childSessionId)
        }
    }

    @Test
    fun `a failed tool keeps its error and drops progress metadata, as the stored state does`() {
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", script.durable()))
        reducer.apply(ServerEvent.ToolProgress(SESSION, MSG_A, "call_1", null, null, mapOf("x" to DataValue.Null), script.live()))
        val error = StructuredError("tool.execution", "File not found")
        reducer.apply(ServerEvent.ToolFailed(SESSION, MSG_A, "call_1", error, false, script.durable()))

        reducer.tool(MSG_A, "call_1").let {
            assertEquals(ToolStatus.Failed, it.status)
            assertEquals(error, it.error)
            assertEquals(emptyMap(), it.metadata)
        }
    }

    @Test
    fun `tools finishing in any order are matched on their ID`() {
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "ok", "read", script.durable()))
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "bad", "read", script.durable()))
        reducer.apply(ServerEvent.ToolFailed(SESSION, MSG_A, "bad", StructuredError("x"), false, script.durable()))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "ok", meta = script.durable()))

        val tools = reducer.assistant(MSG_A).blocks.filterIsInstance<AssistantBlock.Tool>()
        assertEquals(listOf("ok" to ToolStatus.Completed, "bad" to ToolStatus.Failed), tools.map { it.id to it.status })
    }

    @Test
    fun `a late call or input event does not reopen a finished tool`() {
        val started = script.durable()
        val called = script.durable()
        val ended = script.durable()
        val parsed = DataValue.Fields(mapOf("path" to DataValue.Text("a")))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "call_1", listOf(ToolContent.Text("ok")), mapOf("m" to DataValue.Null), false, ended))
        reducer.apply(ServerEvent.ToolProgress(SESSION, MSG_A, "call_1", null, null, mapOf("p" to DataValue.Null), script.live()))
        reducer.apply(ServerEvent.ToolCalled(SESSION, MSG_A, "call_1", parsed, false, called))
        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\":\"a\"}", script.durable()))
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", started))

        reducer.tool(MSG_A, "call_1").let {
            assertEquals(ToolStatus.Completed, it.status)
            assertEquals("read", it.name)
            assertEquals(parsed, it.input)
            assertEquals(mapOf("m" to DataValue.Null), it.metadata)
            assertEquals(started.createdAt, it.createdAt)
            assertEquals(called.createdAt, it.startedAt)
            assertEquals(ended.createdAt, it.completedAt)
        }
    }

    @Test
    fun `a tool seen before its name has an empty name until input started arrives`() {
        reducer.apply(ServerEvent.ToolCalled(SESSION, MSG_A, "call_1", DataValue.Null, null, script.durable()))
        assertEquals("", reducer.tool(MSG_A, "call_1").name)

        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "shell", script.durable()))
        assertEquals("shell", reducer.tool(MSG_A, "call_1").name)
        assertEquals(ToolStatus.Running, reducer.tool(MSG_A, "call_1").status)
    }

    @Test
    fun `tool events without a message are dropped`() {
        val changed = reducer.apply(ServerEvent.ToolInputStarted(SESSION, null, "call_1", "read", script.durable()))

        assertEquals(false, changed)
        assertEquals(emptyList(), reducer.state.messages)
    }
}
