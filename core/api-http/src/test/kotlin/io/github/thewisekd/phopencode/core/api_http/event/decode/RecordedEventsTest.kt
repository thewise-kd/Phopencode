package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionEvent
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every recorded event, from every flow in `manifest.json`, through the decoder. */
class RecordedEventsTest {
    /** The recorded types that have a [ServerEvent] class, and the class each must become. */
    private val covered: Map<String, KClass<out ServerEvent>> = mapOf(
        "server.connected" to ServerEvent.Connected::class,
        "session.created" to ServerEvent.SessionCreated::class,
        "session.deleted" to ServerEvent.SessionDeleted::class,
        "session.renamed" to ServerEvent.SessionRenamed::class,
        "session.archived" to ServerEvent.SessionArchived::class,
        "session.unarchived" to ServerEvent.SessionUnarchived::class,
        "session.forked" to ServerEvent.SessionForked::class,
        "session.execution.started" to ServerEvent.ExecutionStarted::class,
        "session.execution.succeeded" to ServerEvent.ExecutionSucceeded::class,
        "session.execution.failed" to ServerEvent.ExecutionFailed::class,
        "session.execution.interrupted" to ServerEvent.ExecutionInterrupted::class,
        "session.retry.scheduled" to ServerEvent.RetryScheduled::class,
        "session.step.started" to ServerEvent.StepStarted::class,
        "session.step.streamed" to ServerEvent.StepStreamed::class,
        "session.step.ended" to ServerEvent.StepEnded::class,
        "session.step.failed" to ServerEvent.StepFailed::class,
        "session.text.started" to ServerEvent.TextStarted::class,
        "session.text.delta" to ServerEvent.TextDelta::class,
        "session.text.ended" to ServerEvent.TextEnded::class,
        "session.reasoning.started" to ServerEvent.ReasoningStarted::class,
        "session.reasoning.delta" to ServerEvent.ReasoningDelta::class,
        "session.reasoning.ended" to ServerEvent.ReasoningEnded::class,
        "session.tool.input.started" to ServerEvent.ToolInputStarted::class,
        "session.tool.input.ended" to ServerEvent.ToolInputEnded::class,
        "session.tool.called" to ServerEvent.ToolCalled::class,
        "session.tool.progress" to ServerEvent.ToolProgress::class,
        "session.tool.success" to ServerEvent.ToolSucceeded::class,
        "session.tool.failed" to ServerEvent.ToolFailed::class,
        "session.usage.updated" to ServerEvent.UsageUpdated::class,
        "session.compaction.started" to ServerEvent.CompactionStarted::class,
        "session.compaction.delta" to ServerEvent.CompactionDelta::class,
        "session.compaction.ended" to ServerEvent.CompactionEnded::class,
        "session.compaction.failed" to ServerEvent.CompactionFailed::class,
        "session.revert.staged" to ServerEvent.RevertStaged::class,
        "session.revert.cleared" to ServerEvent.RevertCleared::class,
        "session.revert.committed" to ServerEvent.RevertCommitted::class,
        "session.shell.started" to ServerEvent.ShellStarted::class,
        "session.shell.ended" to ServerEvent.ShellEnded::class,
        "session.inbox.enqueued" to ServerEvent.InboxEnqueued::class,
        "session.inbox.delivered" to ServerEvent.InboxDelivered::class,
        "session.inbox.cancelled" to ServerEvent.InboxCancelled::class,
        "session.inbox.delivery.changed" to ServerEvent.InboxDeliveryChanged::class,
        "session.agent.selected" to ServerEvent.AgentSelected::class,
        "session.model.selected" to ServerEvent.ModelSelected::class,
        "form.created" to ServerEvent.FormCreated::class,
        "form.replied" to ServerEvent.FormReplied::class,
        "form.cancelled" to ServerEvent.FormCancelled::class,
        "permission.asked" to ServerEvent.PermissionAsked::class,
        "permission.replied" to ServerEvent.PermissionReplied::class,
    )

    /** Recorded types with no [ServerEvent] class yet: they need core/api types before the app can use them. */
    private val awaitingApiTypes: Set<String> = setOf(
        "agent.updated", "command.updated", "integration.updated", "model.updated", "plugin.updated",
        "provider.updated", "reference.updated", "skill.updated", "websearch.updated", "location.shutdown",
        "mcp.status.changed", "project.updated", "vcs.branch.updated", "worktree.resolved", "worktree.updated",
        "session.instructions.updated", "session.metadata.updated", "session.permissions", "session.synthetic",
        "session.viewed", "shell.created", "shell.exited",
    )

    private class Recorded(val flow: String, val line: Int, val text: String, val type: String, val data: JsonObject)

    private fun recordedEvents(): List<Recorded> {
        val flows = ApiJson.parseToJsonElement(fixture("manifest.json")).jsonObject.getValue("flows").jsonObject.keys
        return flows.flatMap { flow ->
            fixture("$flow/events.jsonl").lines().withIndex().filter { it.value.isNotBlank() }.map { (index, text) ->
                val envelope = ApiJson.parseToJsonElement(text).jsonObject
                Recorded(
                    flow = flow,
                    line = index + 1,
                    text = text,
                    type = envelope.getValue("type").jsonPrimitive.content,
                    data = envelope["data"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
    }

    @Test
    fun `the two lists do not overlap and have the expected sizes`() {
        assertEquals(49, covered.size)
        assertEquals(22, awaitingApiTypes.size)
        assertTrue(covered.keys.intersect(awaitingApiTypes).isEmpty())
    }

    @Test
    fun `every recorded event decodes without error to its own type`() {
        val events = recordedEvents()
        assertTrue(events.size > 500, "expected the whole recording, got ${events.size} events")
        for (event in events) {
            val where = "${event.flow}/events.jsonl:${event.line} (${event.type})"
            val result = EventDecoder.decodeResult(type = null, data = event.text, id = null)
            val decoded = when (result) {
                is DecodeResult.Decoded -> result.event
                is DecodeResult.Malformed -> fail("$where is malformed")
            }
            val expected = covered[event.type]
            if (expected != null) {
                assertEquals(expected, decoded::class, where)
            } else if (event.type in awaitingApiTypes) {
                assertEquals(ServerEvent.Unknown(event.type, decoded.meta), decoded, where)
            } else {
                fail("$where is a new recorded type: add it to one of the two lists")
            }
        }
    }

    @Test
    fun `every recorded type is in one of the lists`() {
        val recordedTypes = recordedEvents().map { it.type }.toSet()
        assertEquals(covered.keys + awaitingApiTypes, recordedTypes)
    }

    @Test
    fun `session events keep the session they were recorded for`() {
        for (event in recordedEvents()) {
            val decoded = EventDecoder.decode(type = null, data = event.text, id = null) as? SessionEvent ?: continue
            val wire = event.data["sessionID"] ?: (event.data["form"] as? JsonObject)?.get("sessionID")
            assertEquals((wire as JsonPrimitive).content, decoded.sessionId.value, "${event.flow}:${event.line}")
        }
    }

    @Test
    fun `every recorded event keeps its event id`() {
        for (event in recordedEvents()) {
            val decoded = EventDecoder.decode(type = null, data = event.text, id = null)
            val wireId = ApiJson.parseToJsonElement(event.text).jsonObject.getValue("id").jsonPrimitive.content
            assertEquals(wireId, decoded.meta.id, "${event.flow}:${event.line}")
        }
    }
}
