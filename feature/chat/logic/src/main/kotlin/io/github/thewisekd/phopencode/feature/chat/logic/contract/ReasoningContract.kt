package io.github.thewisekd.phopencode.feature.chat.logic.contract

import kotlin.time.Duration

/**
 * The model's thinking, one card per reasoning block (P6.7, P6.8).
 *
 * Where it comes from: the `AssistantBlock.Reasoning` blocks of the assistant messages in
 * `ChatSessionState.session`, keyed by [TimelineKey.reasoning] with the block's index, as the
 * timeline keys its [TimelineItem.Reasoning]. The reducer already joins the live deltas into the
 * block's text. A reasoning with no text, which some providers send, gets no card: a key missing
 * from [ReasoningState.cards] draws nothing.
 *
 * @property cards By the key of the timeline item that places the card.
 */
data class ReasoningState(
    val cards: Map<TimelineKey, ReasoningCardState> = emptyMap(),
)

/**
 * @property text The thinking so far; Markdown. Prints masked.
 * @property streaming The block has no `completedAt` yet.
 * @property duration From the block's `createdAt` to its `completedAt`, or to now while it streams;
 *   null without a start time.
 * @property expanded The full text shows; collapsed, a one-line header does.
 */
data class ReasoningCardState(
    val text: String,
    val streaming: Boolean = false,
    val duration: Duration? = null,
    val expanded: Boolean = false,
) {
    override fun toString(): String =
        "ReasoningCardState(streaming=$streaming, duration=$duration, expanded=$expanded, text=***)"
}

sealed interface ReasoningIntent {
    /** Expands a collapsed card, or collapses an expanded one. */
    data class Toggle(val key: TimelineKey) : ReasoningIntent
}

/** Reasoning cards have no one-time events. */
interface ReasoningPresenter : Presenter<ReasoningState, ReasoningIntent, Nothing>
