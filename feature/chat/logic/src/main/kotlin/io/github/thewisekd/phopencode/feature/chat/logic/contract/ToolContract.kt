package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError

/**
 * One card per tool call (P6.9, P6.10).
 *
 * Where it comes from: the `AssistantBlock.Tool` blocks of the assistant messages in
 * `ChatSessionState.session`, keyed by [TimelineKey.tool] with the block's `id`, as the timeline
 * keys its [TimelineItem.Tool]. The tool's name is open: a known one gets its own [ToolSummary], any
 * other one [ToolSummary.Generic].
 *
 * @property cards By the key of the timeline item that places the card.
 */
data class ToolState(
    val cards: Map<TimelineKey, ToolCardState> = emptyMap(),
)

/**
 * @property callId `AssistantBlock.Tool.id`.
 * @property name The tool's name as the server gave it.
 * @property input The input as text to show: formatted when parsed, the raw text so far while
 *   streaming. Prints masked.
 * @property output The result's text parts joined; null before any. The screen shortens a long one.
 *   Prints masked.
 * @property error Why the call failed; [StructuredError] prints no server text.
 * @property expanded The sections shown in full.
 * @property childSessionId The session a subagent call runs in.
 */
data class ToolCardState(
    val callId: String,
    val name: String,
    val status: ToolCardStatus,
    val summary: ToolSummary,
    val input: String? = null,
    val output: String? = null,
    val error: StructuredError? = null,
    val expanded: Set<ToolSection> = emptySet(),
    val childSessionId: SessionId? = null,
) {
    override fun toString(): String =
        "ToolCardState(callId=$callId, name=$name, status=$status, summary=$summary, " +
            "input=${if (input == null) "null" else "***"}, output=${if (output == null) "null" else "***"}, " +
            "error=$error, expanded=$expanded, childSessionId=${childSessionId?.value})"
}

/** From `ToolStatus`. */
enum class ToolCardStatus {
    /** The model is still writing the input. */
    Preparing,
    Running,
    Completed,
    Failed,

    /** A status this app does not know. */
    Unknown,
}

/** What a call does, in one line. Commands and patterns print masked: they can quote a secret. */
sealed interface ToolSummary {
    data class Shell(val command: String) : ToolSummary {
        override fun toString(): String = "Shell(***)"
    }

    /** @property additions Null when the tool reported none. */
    data class Edit(val path: String, val additions: Int? = null, val deletions: Int? = null) : ToolSummary

    data class Read(val path: String) : ToolSummary

    /** @property path Where it searched; null for the project. */
    data class Search(val pattern: String, val path: String? = null) : ToolSummary {
        override fun toString(): String = "Search(pattern=***, path=$path)"
    }

    /** A tool without a summary of its own: its name. */
    data class Generic(val name: String) : ToolSummary
}

enum class ToolSection {
    Input,
    Output,
}

sealed interface ToolIntent {
    /** Shows [section] of the card in full, or shortens it again. */
    data class Toggle(val key: TimelineKey, val section: ToolSection) : ToolIntent
}

/** Tool cards have no one-time events. */
interface ToolPresenter : Presenter<ToolState, ToolIntent, Nothing>
