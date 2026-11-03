package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.time.Instant

/*
 * Mutable drafts of an assistant message, private to SessionReducer. Each field is either filled once
 * by the first event that has it, or replaced only by an event that ends something; so a late or
 * repeated event never undoes a later one.
 */

/** A block's place: blocks are ordered by when they started, else by the earliest event seen for them. */
internal abstract class BlockDraft(val arrival: Long) {
    /** Blocks the message list already held keep that order, ahead of any new ones. */
    var seededIndex: Int? = null
    var startedAt: Instant? = null
    var firstSeenAt: Instant? = null

    fun seen(at: Instant?) {
        firstSeenAt = earliest(firstSeenAt, at)
    }

    val orderTime: Instant?
        get() = startedAt ?: firstSeenAt

    abstract fun toBlock(): AssistantBlock
}

internal enum class TextKind { Text, Reasoning }

/**
 * Streamed pieces of text joined by their time. Deltas are live events with no durable `seq`, so time
 * is the only order they carry. A piece goes before the first one with a later time, else last; a piece
 * without a time goes last and keeps its place.
 */
internal class TimedText {
    private class Chunk(val at: Instant?, val text: String)

    private val chunks = ArrayList<Chunk>()
    private val joined = StringBuilder()

    val text: String
        get() = joined.toString()

    fun add(at: Instant?, piece: String) {
        val later = if (at == null) -1 else chunks.indexOfFirst { it.at != null && it.at > at }
        val position = if (later >= 0) later else chunks.size
        chunks.add(position, Chunk(at, piece))
        if (position == chunks.size - 1) {
            joined.append(piece)
        } else {
            joined.setLength(0)
            chunks.forEach { joined.append(it.text) }
        }
    }
}

/** A text or reasoning block. `ordinal` counts per kind, so (kind, ordinal) is its key. */
internal class TextDraft(val kind: TextKind, val ordinal: Int, arrival: Long) : BlockDraft(arrival) {
    private val deltas = TimedText()

    /** The `*.ended` text. Once set, deltas no longer count. */
    var final: String? = null

    /** The stamp of the `*.ended` event applied, so an older one never replaces it. */
    var endStamp: Stamp? = null
    var completedAt: Instant? = null

    val text: String
        get() = final ?: deltas.text

    /** False once the block has ended. */
    fun addDelta(at: Instant?, delta: String): Boolean {
        if (final != null) return false
        deltas.add(at, delta)
        return true
    }

    /** The message list stores no times for text, so neither does this. */
    override fun toBlock(): AssistantBlock = when (kind) {
        TextKind.Text -> AssistantBlock.Text(text)
        TextKind.Reasoning -> AssistantBlock.Reasoning(text, createdAt = startedAt, completedAt = completedAt)
    }
}

/** A tool call, keyed on the server's call ID. */
internal class ToolDraft(val id: String, arrival: Long) : BlockDraft(arrival) {
    var name: String? = null
    var status: ToolStatus = ToolStatus.Streaming
    private val rawInput = TimedText()
    var input: DataValue? = null

    /** Set once `tool.called` gave the parsed input, which raw text no longer replaces. */
    var inputParsed = false
    var content: List<ToolContent> = emptyList()
    var error: StructuredError? = null
    var calledAt: Instant? = null
    var completedAt: Instant? = null
    var childSessionId: SessionId? = null
    var calledExecuted: Boolean? = null
    var endedExecuted: Boolean? = null
    var metadata: Map<String, DataValue> = emptyMap()

    /** The stamps of the progress and the ending applied, so an older one never replaces them. */
    var progressStamp: Stamp? = null
    var endStamp: Stamp? = null

    val ended: Boolean
        get() = rank(status) >= ENDED

    /** Moves the status forward only: a late event never reopens a call. */
    fun advance(to: ToolStatus) {
        if (rank(to) > rank(status)) status = to
    }

    /** Set once `tool.input.ended` gave the whole raw input, which deltas no longer extend. */
    private var inputEnded = false

    /**
     * Raw input so far, joined by time. Taken whatever the status, so that deltas arriving after the
     * call ended still give an input when the call had none; parsed or complete input wins.
     */
    fun addInputDelta(at: Instant?, delta: String) {
        if (inputParsed || inputEnded) return
        rawInput.add(at, delta)
        input = DataValue.Text(rawInput.text)
    }

    /** The stamp of the input end applied, so an older one never replaces it. */
    private var inputEndStamp: Stamp? = null

    /** The whole raw input. Taken whatever the status, unless `tool.called` gave the parsed input. */
    fun endInput(text: String?, stamp: Stamp) {
        if (inputParsed || text == null || stamp.isOlderThan(inputEndStamp)) return
        inputEndStamp = stamp
        inputEnded = true
        input = DataValue.Text(text)
    }

    /**
     * Takes the input of a fetched list. While the call still streams, raw text there is what the deltas
     * built so far, and later deltas extend it; any other value counts as parsed.
     */
    fun seedInput(value: DataValue?, streaming: Boolean) {
        input = value
        inputParsed = !streaming || (value != null && value !is DataValue.Text)
        if (!inputParsed && value is DataValue.Text) rawInput.add(null, value.value)
    }

    fun call(parsed: DataValue?) {
        if (parsed == null || inputParsed) return
        input = parsed
        inputParsed = true
    }

    override fun toBlock(): AssistantBlock = AssistantBlock.Tool(
        id = id,
        name = name.orEmpty(),
        status = status,
        input = input,
        content = content,
        error = error,
        createdAt = startedAt,
        startedAt = calledAt,
        completedAt = completedAt,
        childSessionId = childSessionId,
        executed = endedExecuted ?: calledExecuted,
        metadata = metadata,
    )

    private companion object {
        const val ENDED = 2

        fun rank(status: ToolStatus): Int = when (status) {
            ToolStatus.Streaming -> 0
            ToolStatus.Running -> 1
            ToolStatus.Completed, ToolStatus.Failed, is ToolStatus.Unknown -> ENDED
        }
    }
}

/** A block of a seeded message this app does not know; kept as it was. */
internal class OpaqueBlock(private val block: AssistantBlock, arrival: Long) : BlockDraft(arrival) {
    override fun toBlock(): AssistantBlock = block
}

/** One step: an assistant message. */
internal class AssistantDraft(val id: MessageId, private val sessionId: SessionId) {
    var createdAt: Instant? = null
    var completedAt: Instant? = null
    var agent: String? = null
    var model: ModelRef? = null
    var usage: TokenUsage? = null
    var finishReason: String? = null
    var error: StructuredError? = null

    /** The stamp of the step end or failure applied, if any. */
    var endStamp: Stamp? = null
    private var arrivals = 0L
    private val texts = HashMap<Pair<TextKind, Int>, TextDraft>()
    private val tools = HashMap<String, ToolDraft>()
    private val blocks = ArrayList<BlockDraft>()

    fun text(kind: TextKind, ordinal: Int): TextDraft =
        texts.getOrPut(kind to ordinal) { TextDraft(kind, ordinal, arrivals++).also(blocks::add) }

    fun tool(id: String): ToolDraft = tools.getOrPut(id) { ToolDraft(id, arrivals++).also(blocks::add) }

    fun toMessage(): Message.Assistant = Message.Assistant(
        id = id,
        sessionId = sessionId,
        createdAt = createdAt,
        completedAt = completedAt,
        agent = agent,
        model = model,
        blocks = blocks.sortedWith(BLOCK_ORDER).map { it.toBlock() },
        usage = usage,
        finishReason = finishReason,
        error = error,
    )

    companion object {
        private val BLOCK_ORDER: Comparator<BlockDraft> =
            compareBy<BlockDraft, Int?>(nullsLast()) { it.seededIndex }.then(orderOf({ it.orderTime }, { it.arrival }))

        /** A message from the list, whose blocks are final until an `*.ended` event replaces them. */
        fun seeded(message: Message.Assistant): AssistantDraft = AssistantDraft(message.id, message.sessionId).apply {
            createdAt = message.createdAt
            completedAt = message.completedAt
            agent = message.agent
            model = message.model
            usage = message.usage
            finishReason = message.finishReason
            error = message.error
            val ordinals = HashMap<TextKind, Int>()
            message.blocks.forEachIndexed { index, block ->
                val draft = when (block) {
                    is AssistantBlock.Text -> seedText(TextKind.Text, block.text, ordinals, null, null)
                    is AssistantBlock.Reasoning -> seedText(TextKind.Reasoning, block.text, ordinals, block.createdAt, block.completedAt)
                    is AssistantBlock.Tool -> tool(block.id).also { it.seed(block) }
                    is AssistantBlock.Unknown -> OpaqueBlock(block, arrivals++).also(blocks::add)
                }
                draft.seededIndex = index
            }
        }

        private fun AssistantDraft.seedText(
            kind: TextKind,
            text: String,
            ordinals: HashMap<TextKind, Int>,
            createdAt: Instant?,
            completedAt: Instant?,
        ): TextDraft {
            val ordinal = ordinals.getOrDefault(kind, 0)
            ordinals[kind] = ordinal + 1
            return text(kind, ordinal).also {
                it.final = text
                it.startedAt = createdAt
                it.completedAt = completedAt
            }
        }

        private fun ToolDraft.seed(block: AssistantBlock.Tool) {
            name = block.name
            status = block.status
            seedInput(block.input, streaming = block.status == ToolStatus.Streaming)
            content = block.content
            error = block.error
            startedAt = block.createdAt
            calledAt = block.startedAt
            completedAt = block.completedAt
            childSessionId = block.childSessionId
            endedExecuted = block.executed
            metadata = block.metadata
        }
    }
}
