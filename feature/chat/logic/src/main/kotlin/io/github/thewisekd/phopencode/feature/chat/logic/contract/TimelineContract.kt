package io.github.thewisekd.phopencode.feature.chat.logic.contract

/**
 * The chat's history, oldest first (P6.3, P6.4).
 *
 * Where it comes from: `ChatSessionState.session` of the chat's [ChatSession], turned into
 * [TimelineItem]s on every change; see [TimelineItem] for the rules. `SessionSync` holds the whole
 * history, so older messages are a window over it: the timeline shows the newest ones first, and
 * [TimelineIntent.LoadOlder] widens the window.
 *
 * The auto-scroll rule: while [followLatest] holds, the screen keeps the newest item in view as items
 * arrive and grow; once the user scrolls away from it, new items only add to [unseenCount].
 *
 * @property loading Nothing is loaded yet.
 * @property items Oldest first.
 * @property hasOlder Older messages are held but not shown yet.
 * @property loadingOlder The window is widening.
 * @property olderError Widening the window failed.
 * @property followLatest The newest item is kept in view.
 * @property unseenCount Items added since the user scrolled away from the newest one.
 */
data class TimelineState(
    val loading: Boolean = true,
    val items: List<TimelineItem> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderError: ChatFailure? = null,
    val followLatest: Boolean = true,
    val unseenCount: Int = 0,
) {
    /** Loaded, with nothing in the session yet: the empty state. */
    val isEmpty: Boolean
        get() = !loading && items.isEmpty()
}

sealed interface TimelineIntent {
    /** The top of [TimelineState.items] came into view. Does nothing without older messages. */
    data object LoadOlder : TimelineIntent

    /**
     * The user scrolled.
     *
     * @property atLatest The newest item is in view.
     */
    data class Scrolled(val atLatest: Boolean) : TimelineIntent

    /** Back to the newest item, following it again; also sent by the chat after a prompt is sent. */
    data object JumpToLatest : TimelineIntent
}

/** The timeline has no one-time events: scrolling follows [TimelineState.followLatest]. */
interface TimelinePresenter : Presenter<TimelineState, TimelineIntent, Nothing>
