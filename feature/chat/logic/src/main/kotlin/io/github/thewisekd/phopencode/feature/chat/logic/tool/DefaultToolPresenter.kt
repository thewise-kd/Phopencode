package io.github.thewisekd.phopencode.feature.chat.logic.tool

import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The tool cards of the chat (P6.9), derived from the `AssistantBlock.Tool` blocks of the
 * assistant messages of the shared session, keyed by [TimelineKey.tool].
 *
 * - The summary comes from the tool's name and input ([ToolSummaries]); a tool without its own,
 *   or a known one whose main input is not there yet, gets the generic summary.
 * - The status maps one to one from `ToolStatus`; a failed call carries its error and keeps its
 *   summary.
 * - The input shows as indented JSON once parsed, and as the raw text while it streams. The output
 *   is the result's text parts joined by line breaks, null before any.
 * - Which sections are expanded is kept per [TimelineKey] for the presenter's life, through updates
 *   and while a card is briefly absent.
 *
 * Tool inputs and outputs can hold secrets: nothing here logs them, and [ToolCardState] prints
 * them masked.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 */
class DefaultToolPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : ToolPresenter {
    private val mutableState = MutableStateFlow(ToolState())
    private val expanded = MutableStateFlow<Map<TimelineKey, Set<ToolSection>>>(emptyMap())

    override val state: StateFlow<ToolState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    init {
        scope.launch {
            // The screen's scope runs immediately: let construction finish before any work.
            yield()
            session.state
                .map { it.session }
                .distinctUntilChanged()
                .combine(expanded) { sessionState, open -> ToolState(cards(sessionState, open)) }
                .collect { mutableState.value = it }
        }
    }

    override fun onIntent(intent: ToolIntent) {
        when (intent) {
            is ToolIntent.Toggle -> {
                if (intent.key !in mutableState.value.cards) return
                expanded.update { open ->
                    val sections = open[intent.key].orEmpty()
                    val toggled = if (intent.section in sections) sections - intent.section else sections + intent.section
                    if (toggled.isEmpty()) open - intent.key else open + (intent.key to toggled)
                }
            }
        }
    }

    private fun cards(
        sessionState: SessionState?,
        open: Map<TimelineKey, Set<ToolSection>>,
    ): Map<TimelineKey, ToolCardState> {
        val messages = sessionState?.messages ?: return emptyMap()
        val cards = LinkedHashMap<TimelineKey, ToolCardState>()
        for (message in messages) {
            if (message !is Message.Assistant) continue
            for (block in message.blocks) {
                if (block !is AssistantBlock.Tool) continue
                val key = TimelineKey.tool(message.id, block.id)
                cards[key] = card(block, open[key].orEmpty())
            }
        }
        return cards
    }

    private fun card(block: AssistantBlock.Tool, expanded: Set<ToolSection>): ToolCardState =
        ToolCardState(
            callId = block.id,
            name = block.name,
            status = status(block.status),
            summary = ToolSummaries.of(block.name, block.input, block.metadata),
            input = block.input?.let(ToolInputFormat::format),
            output = block.content
                .filterIsInstance<ToolContent.Text>()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.text },
            error = block.error,
            expanded = expanded,
            childSessionId = block.childSessionId,
        )

    private fun status(status: ToolStatus): ToolCardStatus =
        when (status) {
            ToolStatus.Streaming -> ToolCardStatus.Preparing
            ToolStatus.Running -> ToolCardStatus.Running
            ToolStatus.Completed -> ToolCardStatus.Completed
            ToolStatus.Failed -> ToolCardStatus.Failed
            is ToolStatus.Unknown -> ToolCardStatus.Unknown
        }
}
