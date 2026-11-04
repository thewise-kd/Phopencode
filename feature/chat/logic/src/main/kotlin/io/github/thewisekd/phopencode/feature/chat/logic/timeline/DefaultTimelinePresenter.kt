package io.github.thewisekd.phopencode.feature.chat.logic.timeline

import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The chat's timeline: turns `ChatSessionState.session` into [TimelineItem]s (see `timelineItems`),
 * shows a window over them, and keeps the auto-scroll rule.
 *
 * - The window: `SessionSync` holds the whole history, so older messages are already here. The
 *   window starts at the newest [pageSize] messages and is held by its oldest message, so messages
 *   that arrive widen it rather than push old ones out; [TimelineIntent.LoadOlder] adds [pageSize]
 *   older ones at once, so [TimelineState.loadingOlder] and [TimelineState.olderError] stay unset.
 *   Prompts waiting in the inbox always show, below the window.
 * - Following: [TimelineState.followLatest] holds until [TimelineIntent.Scrolled] says the newest
 *   item is out of view. While it does not hold, every item under a key not shown since, apart from
 *   older history the window takes in at its top, adds to [TimelineState.unseenCount]; text that grows and a prompt
 *   delivered keep their key and add nothing, even when the prompt leaves the inbox an update
 *   before its message arrives. Scrolling back to the newest item, or
 *   [TimelineIntent.JumpToLatest], follows again and clears the count.
 * - No session (not loaded yet, not found, or another server's connection): the initial state, and
 *   the window starts over when a session comes. A drop keeps the session, so the timeline stays.
 *
 * It only reads [ChatSession.state], which never carries another server's session; it asks the
 * server nothing. Work starts after a `yield()`, never in the constructor. Nothing here logs.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 * @param pageSize How many messages the window shows at first, and adds on each
 *   [TimelineIntent.LoadOlder].
 */
class DefaultTimelinePresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : TimelinePresenter {
    private val mutableState = MutableStateFlow(TimelineState())

    override val state: StateFlow<TimelineState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    private val page = pageSize.coerceAtLeast(1)

    /** Guards the fields below and the writes of [mutableState], so they change together. */
    private val lock = Any()

    /** The session last seen; null while there is none. */
    private var held: SessionState? = null

    /** The oldest message in the window, and how many messages the window held when last built. */
    private var anchor: MessageId? = null
    private var shown = 0

    /**
     * Every key shown since the user last left the newest item; while following, the keys shown
     * now. A key that leaves the timeline for a while, such as a prompt between leaving the inbox
     * and arriving as a message, is not new when it comes back. Bounded by what one stretch away
     * from the newest item shows, and cleared with the session.
     */
    private val seen = HashSet<TimelineKey>()

    /** The messages in the window when last built, to tell older ones it may take in from new ones. */
    private var windowIds: Set<MessageId> = emptySet()

    init {
        scope.launch {
            yield()
            session.state.collect(::onSession)
        }
    }

    override fun onIntent(intent: TimelineIntent) {
        synchronized(lock) {
            val current = mutableState.value
            mutableState.value = when (intent) {
                TimelineIntent.LoadOlder -> {
                    val loaded = held ?: return
                    val start = windowStart(loaded)
                    if (start == 0) return
                    render(loaded, (start - page).coerceAtLeast(0), current).also { wider ->
                        wider.items.mapTo(seen) { it.key }
                    }
                }
                is TimelineIntent.Scrolled ->
                    if (intent.atLatest) {
                        current.copy(followLatest = true, unseenCount = 0)
                    } else {
                        current.copy(followLatest = false)
                    }
                TimelineIntent.JumpToLatest -> current.copy(followLatest = true, unseenCount = 0)
            }
        }
    }

    private fun onSession(chat: ChatSessionState) {
        synchronized(lock) {
            val loaded = chat.session
            held = loaded
            if (loaded == null) {
                anchor = null
                shown = 0
                seen.clear()
                windowIds = emptySet()
                mutableState.value = TimelineState()
                return
            }
            val current = mutableState.value
            val start = windowStart(loaded)
            val older = olderIds(loaded, start)
            val next = render(loaded, start, current)
            mutableState.value = if (current.loading || current.followLatest) {
                seen.clear()
                next.items.mapTo(seen) { it.key }
                next
            } else {
                val unseen = next.items.count { it.key !in seen && it.messageId !in older }
                next.items.mapTo(seen) { it.key }
                next.copy(unseenCount = current.unseenCount + unseen)
            }
        }
    }

    /**
     * Where the window starts in [loaded]'s messages: at its anchor; at the newest page before there
     * is one; or, when the anchor is gone, as many messages back as the window held.
     */
    private fun windowStart(loaded: SessionState): Int {
        val messages = loaded.messages
        val oldest = anchor ?: return (messages.size - page).coerceAtLeast(0)
        val index = messages.indexOfFirst { it.id == oldest }
        return if (index >= 0) index else (messages.size - shown).coerceAtLeast(0)
    }

    /** The window from [start], with the inbox prompts not yet among the messages below it. */
    private fun render(loaded: SessionState, start: Int, current: TimelineState): TimelineState {
        val messages = loaded.messages
        anchor = messages.getOrNull(start)?.id
        shown = messages.size - start
        windowIds = messages.subList(start, messages.size).mapTo(HashSet()) { it.id }
        val delivered = messages.mapTo(HashSet()) { it.id }
        val items = timelineItems(
            messages = messages.subList(start, messages.size),
            inbox = loaded.inbox.filter { it.id !in delivered },
        )
        return current.copy(loading = false, items = items, hasOlder = start > 0)
    }

    /**
     * The messages a window from [start] takes in above the oldest one the last window held: older
     * history, never new. Empty when none of the last window is left.
     */
    private fun olderIds(loaded: SessionState, start: Int): Set<MessageId> {
        val messages = loaded.messages
        val firstKept = messages.indexOfFirst { it.id in windowIds }
        if (firstKept <= start) return emptySet()
        return messages.subList(start, firstKept).mapTo(HashSet()) { it.id }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
