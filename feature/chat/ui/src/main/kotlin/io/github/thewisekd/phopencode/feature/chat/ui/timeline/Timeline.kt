package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.ui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How many items from the top count as near it, for asking for older ones. */
private const val LOAD_AHEAD = 2

/**
 * At most this many viewport heights per catch-up with the end, so one frame stays short; a longer
 * way goes on in the next frames.
 */
internal const val FOLLOW_STEPS_PER_FRAME = 1_000

/**
 * The chat's history, oldest at the top (P6.4). Draws [state], sends [onIntent]; the presenter
 * decides everything.
 *
 * Items keep their [TimelineItem.key] as the list key. A [TimelineItem.Reasoning] and a
 * [TimelineItem.Tool] are drawn by [reasoningCard] and [toolCard], which the chat fills from their
 * own presenters; the timeline only places them. A prompt sits in a bubble on the end side; a reply
 * spans the width from the start side and renders its Markdown through `MarkdownView`.
 *
 * Scrolling follows [TimelineState.followLatest]: while it holds, the list keeps its end in view as
 * items arrive and a streamed reply grows. When the user moves the list, it sends
 * [TimelineIntent.Scrolled] with whether the end is in view; growth and the timeline's own catching
 * up are not the user moving it, so they send nothing that would stop the following. While not
 * following, the list stays where the user put it, and a button that counts
 * [TimelineState.unseenCount] sends [TimelineIntent.JumpToLatest]. The top coming into view sends
 * [TimelineIntent.LoadOlder]; older items join above the first one on screen, which keeps its place
 * by its key, and the older items' progress or failure floats over the list, so nothing on screen
 * moves.
 *
 * Progress is shown as still messages, never core/ui's `Loading`: its spinner never stops, and the
 * app's ActivityScenario tests would never come to rest.
 *
 * Message text is user data: it is drawn, never logged.
 */
@Composable
fun Timeline(
    state: TimelineState,
    onIntent: (TimelineIntent) -> Unit,
    reasoningCard: @Composable (TimelineItem.Reasoning) -> Unit,
    toolCard: @Composable (TimelineItem.Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    TimelineContent(state, onIntent, reasoningCard, toolCard, modifier, FOLLOW_STEPS_PER_FRAME)
}

/** [Timeline], with how far one catch-up with the end may go open for tests. */
@Composable
internal fun TimelineContent(
    state: TimelineState,
    onIntent: (TimelineIntent) -> Unit,
    reasoningCard: @Composable (TimelineItem.Reasoning) -> Unit,
    toolCard: @Composable (TimelineItem.Tool) -> Unit,
    modifier: Modifier,
    followStepsPerFrame: Int,
) {
    Box(modifier) {
        when {
            state.items.isNotEmpty() -> TimelineList(state, onIntent, reasoningCard, toolCard, followStepsPerFrame)
            state.loading -> StillProgress(stringResource(R.string.chat_timeline_loading), Modifier.fillMaxSize())
            else -> EmptyState(
                modifier = Modifier.fillMaxSize(),
                title = stringResource(R.string.chat_timeline_empty_title),
                message = stringResource(R.string.chat_timeline_empty_message),
            )
        }
    }
}

/** The list, the older items' status over its top, and the jump button over its bottom. */
@Composable
private fun BoxScope.TimelineList(
    state: TimelineState,
    onIntent: (TimelineIntent) -> Unit,
    reasoningCard: @Composable (TimelineItem.Reasoning) -> Unit,
    toolCard: @Composable (TimelineItem.Tool) -> Unit,
    followStepsPerFrame: Int,
) {
    val listState = rememberLazyListState()
    val tracker = remember { FollowTracker() }
    val takeOver = remember(tracker) { TakeOverDetector(tracker) }
    val send by rememberUpdatedState(onIntent)
    FollowLatest(listState, state.followLatest, tracker, followStepsPerFrame)
    ReportScrolling(listState, state.followLatest, tracker) { send(it) }
    LoadOlderNearTheTop(listState, state) { send(it) }

    val spacing = PhopencodeTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize().nestedScroll(takeOver).testTag(TIMELINE_LIST_TAG),
        state = listState,
    ) {
        items(state.items, key = { it.key.value }, contentType = { it::class }) { item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .testTag(timelineItemTag(item.key))
                    .padding(horizontal = spacing.lg, vertical = spacing.xs),
            ) {
                TimelineEntry(item, reasoningCard, toolCard)
            }
        }
    }
    OlderStatus(state, Modifier.align(Alignment.TopCenter)) { send(TimelineIntent.LoadOlder) }
    if (!state.followLatest) {
        JumpToLatest(state.unseenCount, Modifier.align(Alignment.BottomCenter)) { send(TimelineIntent.JumpToLatest) }
    }
}

/**
 * What the follow rule knows ahead of the presenter.
 *
 * [ownPosition]: where the timeline's own catching up left the list, so that [ReportScrolling] does
 * not take it for the user's scrolling; null once the list has moved elsewhere.
 *
 * [userTookOver]: the user scrolled towards the start, so no catch-up may run, even before
 * [TimelineIntent.Scrolled] has come back as [TimelineState.followLatest]. A short swipe can end
 * before [ReportScrolling] sees it, and a catch-up must not undo it meanwhile. Cleared when the list
 * is at the end while following, or when the state says to follow again after it did not.
 */
private class FollowTracker {
    var ownPosition: Pair<Int, Int>? = null
    var userTookOver: Boolean by mutableStateOf(false)
}

/**
 * Marks [FollowTracker.userTookOver] the moment a drag, fling or mouse wheel has really moved the list
 * towards the start: nested scrolling calls it in the same pass, before any coroutine runs. Only the
 * user's scrolling passes through here; the catch-up's own does not. Moving towards the end, pulling
 * past it, or dragging a history too short to scroll moves nothing away from the newest item, so it
 * takes nothing over.
 *
 * A fling's frames come through [onPostScroll] with what they really moved. The fling's own end is
 * not used: on a list that cannot scroll, it reports the whole velocity as consumed.
 */
private class TakeOverDetector(private val tracker: FollowTracker) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y > 0f) tracker.userTookOver = true
        return Offset.Zero
    }
}

private val LazyListState.position: Pair<Int, Int>
    get() = firstVisibleItemIndex to firstVisibleItemScrollOffset

/**
 * While [followLatest] holds, keeps the end in view: an item arrives, a streamed reply grows, a card
 * opens. The end leaves the view only after a layout, so a catch-up runs in the next frame.
 *
 * One rule for every catch-up that ends short of the end, whatever the reason (a far way cut at
 * [maxSteps], a user's touch holding the list): try again in the next frame, until the end is in
 * view or the user's scrolling turns the following off. A catch-up never takes the list from anyone:
 * while a drag, a fling or an accessibility scroll moves it, the catch-up waits for it to end.
 */
@Composable
private fun FollowLatest(listState: LazyListState, followLatest: Boolean, tracker: FollowTracker, maxSteps: Int) {
    LaunchedEffect(listState, followLatest) {
        if (!followLatest) return@LaunchedEffect
        tracker.userTookOver = false
        // The end in view while following: nothing was taken over, whatever happened before.
        launch {
            snapshotFlow { !listState.canScrollForward }.collect { atEnd -> if (atEnd) tracker.userTookOver = false }
        }
        while (true) {
            snapshotFlow {
                listState.canScrollForward && !listState.isScrollInProgress && !tracker.userTookOver
            }.first { it }
            scrollToEnd(listState, tracker, maxSteps)
            withFrameNanos {}
        }
    }
}

/**
 * Scrolls towards the end, at most [maxSteps] viewport heights, with no suspension between steps, so
 * nothing sees the list half-way. Does nothing while anything else scrolls the list: the catch-up's
 * priority is the lowest there is, equal to an accessibility scroll's, so starting it would cancel
 * that scroll. A user's touch that starts meanwhile cancels the catch-up, which is not this
 * coroutine's end.
 */
private suspend fun scrollToEnd(listState: LazyListState, tracker: FollowTracker, maxSteps: Int) {
    if (listState.isScrollInProgress || tracker.userTookOver) return
    try {
        listState.scroll(MutatePriority.Default) {
            var steps = 0
            while (listState.canScrollForward && steps < maxSteps) {
                val step = listState.layoutInfo.viewportSize.height.coerceAtLeast(1).toFloat()
                if (scrollBy(step) == 0f) break
                steps++
            }
            tracker.ownPosition = listState.position
        }
    } catch (interrupted: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/**
 * Sends [TimelineIntent.Scrolled] when the list moves by anything but [FollowLatest]: a drag, a
 * fling, a mouse wheel, an accessibility action. Growth below the first item on screen does not
 * move the list, so a streamed reply never stops the following. Sends only a change from
 * [followLatest].
 */
@Composable
private fun ReportScrolling(
    listState: LazyListState,
    followLatest: Boolean,
    tracker: FollowTracker,
    send: (TimelineIntent) -> Unit,
) {
    val following by rememberUpdatedState(followLatest)
    LaunchedEffect(listState) {
        snapshotFlow { listState.position }
            .drop(1)
            .collect { position ->
                if (position == tracker.ownPosition) return@collect
                tracker.ownPosition = null
                val atLatest = !listState.canScrollForward
                if (atLatest) tracker.userTookOver = false
                if (atLatest != following) send(TimelineIntent.Scrolled(atLatest))
            }
    }
}

/**
 * Sends [TimelineIntent.LoadOlder] when the top comes into view, while older items are held and
 * nothing loads or failed: a failure waits for the user's retry. While following, the list is about
 * to catch up with the end, so the top counts only once it has. Each window restarts the check, so
 * a top still in view after one asks again.
 */
@Composable
private fun LoadOlderNearTheTop(listState: LazyListState, state: TimelineState, send: (TimelineIntent) -> Unit) {
    val canAsk = state.hasOlder && !state.loadingOlder && state.olderError == null
    val followLatest = state.followLatest
    LaunchedEffect(listState, canAsk, followLatest, state.items.size) {
        if (!canAsk) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.totalItemsCount > 0 &&
                listState.firstVisibleItemIndex <= LOAD_AHEAD &&
                (!followLatest || !listState.canScrollForward)
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { send(TimelineIntent.LoadOlder) }
    }
}

/** Older items loading or failed, over the top of the list so the list itself does not move. */
@Composable
private fun OlderStatus(state: TimelineState, modifier: Modifier, onRetry: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    when {
        state.loadingOlder -> Surface(
            modifier = modifier.padding(spacing.sm),
            shape = MaterialTheme.shapes.large,
            tonalElevation = spacing.xxs,
            shadowElevation = spacing.xxs,
        ) {
            StillProgress(stringResource(R.string.chat_timeline_loading_older))
        }
        state.olderError != null -> Surface(
            modifier = modifier.padding(spacing.sm),
            shape = MaterialTheme.shapes.large,
            tonalElevation = spacing.xxs,
            shadowElevation = spacing.xxs,
        ) {
            Row(
                modifier = Modifier.padding(start = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_timeline_older_failed),
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = spacing.touchTarget)) {
                    Text(stringResource(R.string.chat_timeline_retry))
                }
            }
        }
    }
}

/** Back to the newest item; counts what arrived out of view. */
@Composable
private fun JumpToLatest(unseenCount: Int, modifier: Modifier, onClick: () -> Unit) {
    val label = if (unseenCount > 0) {
        pluralStringResource(R.plurals.chat_timeline_unseen, unseenCount, unseenCount)
    } else {
        stringResource(R.string.chat_timeline_jump_latest)
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .padding(PhopencodeTheme.spacing.sm)
            .heightIn(min = PhopencodeTheme.spacing.touchTarget)
            .testTag(TIMELINE_JUMP_TAG),
    ) {
        Text(label)
    }
}
