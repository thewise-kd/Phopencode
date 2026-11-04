package io.github.thewisekd.phopencode.feature.chat.logic.reasoning

import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The reasoning cards of the chat (P6.7), derived from the assistant messages of the shared
 * session.
 *
 * - The text is the block's text as the reducer keeps it: deltas are already joined there, and a
 *   resync replaces the block rather than adding to it, so text is never doubled here.
 * - A block with blank text gets no card.
 * - A block streams while it has no `completedAt` and its message has neither ended nor failed.
 * - The duration runs from the block's `createdAt` to its `completedAt`; without that end, to the
 *   message's `completedAt` once the message ended, or to [clock] while it streams, refreshed every
 *   second. Never negative; null without a start time, or when a failed message gives no end.
 * - Expanded or collapsed is kept per [TimelineKey] for the presenter's life, through updates and
 *   while a card is briefly absent.
 *
 * Reasoning text is user data: nothing here logs it, and [ReasoningCardState] prints it masked.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 * @param clock The duration of a reasoning that still streams runs to its `now()`.
 */
class DefaultReasoningPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ReasoningPresenter {
    private val mutableState = MutableStateFlow(ReasoningState())
    private val expanded = MutableStateFlow<Set<TimelineKey>>(emptySet())

    override val state: StateFlow<ReasoningState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    init {
        scope.launch {
            // The screen's scope runs immediately: let construction finish before any work.
            yield()
            session.state
                .map { it.session }
                .distinctUntilChanged()
                .combine(expanded) { sessionState, open -> sessionState to open }
                .collectLatest { (sessionState, open) ->
                    while (true) {
                        val cards = cards(sessionState, open)
                        mutableState.value = ReasoningState(cards)
                        if (cards.values.none { it.streaming && it.duration != null }) break
                        delay(TICK)
                    }
                }
        }
    }

    override fun onIntent(intent: ReasoningIntent) {
        when (intent) {
            is ReasoningIntent.Toggle -> {
                if (intent.key !in mutableState.value.cards) return
                expanded.update { if (intent.key in it) it - intent.key else it + intent.key }
            }
        }
    }

    private fun cards(sessionState: SessionState?, open: Set<TimelineKey>): Map<TimelineKey, ReasoningCardState> {
        val messages = sessionState?.messages ?: return emptyMap()
        val now = clock.now()
        val cards = LinkedHashMap<TimelineKey, ReasoningCardState>()
        for (message in messages) {
            if (message !is Message.Assistant) continue
            val messageEnded = message.completedAt != null || message.error != null
            message.blocks.forEachIndexed { index, block ->
                if (block !is AssistantBlock.Reasoning || block.text.isBlank()) return@forEachIndexed
                val key = TimelineKey.reasoning(message.id, index)
                val streaming = block.completedAt == null && !messageEnded
                val end = block.completedAt ?: message.completedAt ?: if (streaming) now else null
                cards[key] = ReasoningCardState(
                    text = block.text,
                    streaming = streaming,
                    duration = duration(block.createdAt, end),
                    expanded = key in open,
                )
            }
        }
        return cards
    }

    private fun duration(start: Instant?, end: Instant?): Duration? {
        if (start == null || end == null) return null
        return (end - start).coerceAtLeast(Duration.ZERO)
    }

    private companion object {
        val TICK = 1.seconds
    }
}
