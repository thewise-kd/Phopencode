package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.ModelRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeltaMergingTest {
    private val script = EventScript()
    private val reducer = SessionReducer(SESSION)

    private fun stepStarted(id: io.github.thewisekd.phopencode.core.model.MessageId = MSG_A) = ServerEvent.StepStarted(
        SESSION, id, agent = "build", model = ModelRef("sim", "sim-1"), startedAt = EventScript.at(0), meta = script.durable(),
    )

    @Test
    fun `text deltas append to their block while the message streams`() {
        reducer.apply(stepStarted())
        reducer.apply(ServerEvent.TextStarted(SESSION, MSG_A, 0, script.durable()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "Hel", script.live()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "lo", script.live()))

        assertEquals(listOf("Hello"), reducer.texts(MSG_A))
        assertNull(reducer.assistant(MSG_A).completedAt)
    }

    @Test
    fun `the ended text replaces what the deltas built`() {
        reducer.apply(stepStarted())
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "Hel", script.live()))
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "Hello!", script.durable()))

        assertEquals(listOf("Hello!"), reducer.texts(MSG_A))
    }

    @Test
    fun `a delta after the ended text is ignored`() {
        reducer.apply(stepStarted())
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "Final.", script.durable()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, " late", script.live()))
        reducer.apply(ServerEvent.TextStarted(SESSION, MSG_A, 0, script.durable()))

        assertEquals(listOf("Final."), reducer.texts(MSG_A))
    }

    @Test
    fun `deltas are placed by their time, not their arrival`() {
        val first = script.live()
        val second = script.live()
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, " world", second))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "Hello", first))

        assertEquals(listOf("Hello world"), reducer.texts(MSG_A))
    }

    @Test
    fun `a delta for an unknown message creates it, and the step start fills it in later`() {
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "early", script.live()))

        val placeholder = reducer.assistant(MSG_A)
        assertEquals(listOf("early"), reducer.texts(MSG_A))
        assertNull(placeholder.agent)

        reducer.apply(stepStarted())

        val filled = reducer.assistant(MSG_A)
        assertEquals("build", filled.agent)
        assertEquals(ModelRef("sim", "sim-1"), filled.model)
        assertEquals(EventScript.at(0), filled.createdAt)
        assertEquals(listOf("early"), reducer.texts(MSG_A))
    }

    @Test
    fun `a repeated delta is applied once`() {
        val meta = script.live()
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "once", meta))
        val changed = reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "once", meta))

        assertEquals(listOf("once"), reducer.texts(MSG_A))
        assertEquals(false, changed)
    }

    @Test
    fun `text and reasoning with the same ordinal are separate blocks, ordered by when they started`() {
        reducer.apply(stepStarted())
        reducer.apply(ServerEvent.ReasoningStarted(SESSION, MSG_A, 0, script.durable()))
        reducer.apply(ServerEvent.TextStarted(SESSION, MSG_A, 0, script.durable()))
        reducer.apply(ServerEvent.TextStarted(SESSION, MSG_A, 1, script.durable()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 1, "second", script.live()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "first", script.live()))
        reducer.apply(ServerEvent.ReasoningDelta(SESSION, MSG_A, 0, "thinking", script.live()))

        val blocks = reducer.assistant(MSG_A).blocks
        assertEquals(3, blocks.size)
        assertEquals("thinking", (blocks[0] as AssistantBlock.Reasoning).text)
        assertEquals("first", (blocks[1] as AssistantBlock.Text).text)
        assertEquals("second", (blocks[2] as AssistantBlock.Text).text)
    }

    @Test
    fun `an event without an ID is still applied`() {
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "a", EventMeta()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "b", EventMeta()))

        assertEquals(listOf("ab"), reducer.texts(MSG_A))
    }
}
