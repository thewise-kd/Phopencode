package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionEvent
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays each recorded stream up to the moment a message list was fetched, and compares the result
 * with that list.
 *
 * Some list entries have no event that names their ID, so no event stream can produce them; those are
 * left out of the comparison, and only those:
 *
 * - `idle`, `agent-switched` and `model-switched`: the events that end an execution or switch the
 *   agent or model carry no message ID.
 * - `shell`: `session.shell.*` carries the shell's ID, not the message's.
 * - In `retry`, the `synthetic` message the server adds after a dropped stream: it comes from
 *   `session.synthetic`, which has no message ID and no decoder.
 *
 * What the excluded entries say is still checked where the stream has it: each `idle` entry's outcome
 * against the reducer's outcome after the last execution.
 */
class RecordedReplayTest {
    private class Case(val flow: String, val label: String, val excluded: Set<String> = emptySet())

    private val cases = listOf(
        Case("text", "final-messages"),
        Case("text", "messages-asc"),
        Case("reasoning", "final-messages"),
        Case("tool", "final-messages"),
        Case("subagent", "final-messages"),
        Case("subagent", "child-messages"),
        Case("subagent-permission", "child-messages"),
        Case("permission", "final-messages"),
        Case("form-question", "final-messages"),
        Case("interrupt", "final-messages"),
        Case("error", "final-messages"),
        Case("error", "unavailable-messages"),
        Case("retry", "final-messages", excluded = setOf("msg_00000000000000000000000056")),
        Case("compaction", "final-messages"),
        Case("delivery", "final-messages"),
        Case("auto-title", "final-messages"),
        Case("commands", "final-messages"),
        Case("lifecycle", "messages"),
        Case("diff-revert", "messages-asc"),
        Case("diff-revert", "messages-staged"),
        Case("diff-revert", "final-messages"),
    )

    private fun Message.derivable(): Boolean =
        this !is Message.Idle && this !is Message.AgentSwitched && this !is Message.ModelSwitched && this !is Message.Shell

    @Test
    fun `replaying a recorded stream gives the message list fetched at that point`() {
        for (case in cases) {
            val flow = RecordedFlow(case.flow)
            val session = flow.sessionOf(case.label)
            val reducer = SessionReducer(session)
            reducer.applyAll(flow.events.take(flow.eventsBefore(case.label)))

            val listed = flow.messages(case.label)
            val expected = listed.filter { it.derivable() && it.id.value !in case.excluded }
            assertEquals(expected, reducer.state.messages, "${case.flow} ${case.label}")

            val lastIdle = listed.filterIsInstance<Message.Idle>().lastOrNull()
            if (lastIdle != null && listed.last() is Message.Idle) {
                assertEquals(lastIdle.outcome, reducer.state.session.outcome, "${case.flow} ${case.label}")
                assertEquals(lastIdle.error, reducer.state.error, "${case.flow} ${case.label}")
                assertEquals(SessionStatus.Idle, reducer.state.status, "${case.flow} ${case.label}")
            }
        }
    }

    @Test
    fun `the comparison covers every message type the stream can name`() {
        val types = cases.flatMap { case -> RecordedFlow(case.flow).messages(case.label).filter { it.derivable() } }
            .map { it::class.simpleName }
            .toSet()

        assertEquals(setOf("User", "Assistant", "Synthetic", "Compaction"), types)
    }

    @Test
    fun `a session seeded with a fetched list keeps it, and later events merge into it`() {
        val flow = RecordedFlow("text")
        val label = "final-messages"
        val session = flow.sessionOf(label)
        val listed = flow.messages(label).filter { it.derivable() }
        val reducer = SessionReducer(session, messages = listed)

        assertEquals(listed, reducer.state.messages)

        reducer.applyAll(flow.events)

        assertEquals(listed, reducer.state.messages)
        assertEquals(SessionOutcome.Succeeded, reducer.state.session.outcome)
    }

    @Test
    fun `every recorded permission is settled by the end of its flow`() {
        for (name in listOf("permission", "subagent-permission")) {
            val flow = RecordedFlow(name)
            val sessions = flow.events.filterIsInstance<SessionEvent>()
                .map { it.sessionId }
                .toSet()
            for (session in sessions) {
                val reducer = SessionReducer(session).apply { applyAll(flow.events) }
                assertTrue(reducer.state.permissions.isEmpty(), "$name ${session.value}")
            }
        }
    }

    @Test
    fun `a form is pending between created and replied`() {
        val flow = RecordedFlow("form-question")
        val session = flow.sessionOf("final-messages")
        val created = flow.events.indexOfFirst { it is ServerEvent.FormCreated }
        val reducer = SessionReducer(session).apply { applyAll(flow.events.take(created + 1)) }

        assertEquals(1, reducer.state.forms.size)

        reducer.applyAll(flow.events.drop(created + 1).takeWhile { it !is ServerEvent.FormReplied })
        assertEquals(1, reducer.state.forms.size)
        reducer.applyAll(flow.events)
        assertEquals(emptyList(), reducer.state.forms)
    }
}
