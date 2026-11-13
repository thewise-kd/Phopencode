package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.ui.R
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** The panel covers this part of the page's height, from the bottom up; the page stays usable above. */
private const val PANEL_HEIGHT_FRACTION = 0.5f

private val LEVEL_ICON_SIZE = 18.dp

/**
 * Takes up whatever scroll or fling the panel's list leaves over, so that pulling the list down past
 * its first entry never reaches the page's pull to refresh and reloads it.
 */
private object KeepScrollInside : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * The preview's console (P13.14): a panel over the bottom of the page that lists [console]'s
 * entries under its filter, oldest first, each with its level's icon and colour, its text, and its
 * source and line. The page logic fills it from the WebView's `onConsoleMessage` and its load-error,
 * HTTP-error and refused-request callbacks ([PageIntent.Report]); the panel only reads it.
 *
 * - **Filter:** a chip per level and one for all send [PageIntent.FilterConsole].
 * - **Clear:** sends [PageIntent.ClearConsole]; off while there is nothing to clear.
 * - **Hide:** [onHide] folds the panel away; the toolbar's [ConsoleButton] opens it again.
 * - **Text:** selectable, so the user can copy it. It is the page's own text: the panel sends
 *   nothing but the filter and clear, never an entry's text, and logs nothing.
 * - **TalkBack:** reads each entry as one item, its level, its text and where it came from.
 * - **Scrolling:** the list follows the newest entry while it is at its end, and stays put while
 *   the user reads further up.
 */
@Composable
internal fun ConsolePanel(
    console: ConsoleState,
    onIntent: (PageIntent) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(PANEL_HEIGHT_FRACTION)
            .nestedScroll(KeepScrollInside)
            .testTag(ConsoleTags.PANEL),
        shape = MaterialTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(canClear = console.entries.isNotEmpty(), onClear = { onIntent(PageIntent.ClearConsole) }, onHide = onHide)
            Filters(selected = console.filter, onSelect = { onIntent(PageIntent.FilterConsole(it)) })
            HorizontalDivider()
            val shown = console.shown
            if (shown.isEmpty()) {
                Empty(filtered = console.entries.isNotEmpty())
            } else {
                Entries(shown)
            }
        }
    }
}

@Composable
private fun Header(canClear: Boolean, onClear: () -> Unit, onHide: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = PhopencodeTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.preview_console_title),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onClear, enabled = canClear, modifier = Modifier.testTag(ConsoleTags.CLEAR)) {
            Text(stringResource(R.string.preview_console_clear), maxLines = 1)
        }
        IconButton(onClick = onHide, modifier = Modifier.testTag(ConsoleTags.HIDE)) {
            Icon(imageVector = PhopencodeIcons.Close, contentDescription = stringResource(R.string.preview_console_hide))
        }
    }
}

/**
 * A chip for every level, after one for all of them. They wrap onto more lines rather than scroll
 * sideways, so that no chip is ever cut in half at the edge with a target too small to hit.
 */
@Composable
private fun Filters(selected: ConsoleLevel?, onSelect: (ConsoleLevel?) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhopencodeTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.preview_console_filter_all), maxLines = 1) },
            modifier = Modifier.testTag(ConsoleTags.filter(null)),
        )
        ConsoleLevel.entries.forEach { level ->
            val accent = level.style().accent
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level.label(), maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = level.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(LEVEL_ICON_SIZE),
                    )
                },
                modifier = Modifier.testTag(ConsoleTags.filter(level)),
            )
        }
    }
}

@Composable
private fun ColumnScope.Empty(filtered: Boolean) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PhopencodeTheme.spacing.lg)
            .testTag(ConsoleTags.EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (filtered) R.string.preview_console_empty_filtered else R.string.preview_console_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.Entries(shown: List<ConsoleEntry>) {
    val list = rememberLazyListState()
    FollowNewest(list, shown)
    SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag(ConsoleTags.LIST)) {
            itemsIndexed(shown) { index, entry ->
                EntryRow(entry, Modifier.testTag(ConsoleTags.entry(index)))
            }
        }
    }
}

/**
 * Keeps the newest entry in view while the user stays at the end. Following stops the moment the
 * user's finger starts moving the list, so a new entry never cuts a scroll or fling short and pulls
 * the list down; it starts again once the list is back at its end, whether a drag stops there or a
 * fling or a later drag reaches it.
 *
 * Any other scroll, such as TalkBack's scroll actions, counts by where it leaves the list: away
 * from the end stops following, at the end starts it again. New entries do not move the list's
 * first visible item, so they never count as a scroll; the panel's own jump to the end does, and
 * ends at the end.
 */
@Composable
private fun FollowNewest(list: LazyListState, shown: List<ConsoleEntry>) {
    var follow by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragging = true
                    follow = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    if (!list.canScrollForward) follow = true
                }
            }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward }
            .filter { canScrollForward -> !canScrollForward }
            .collect { if (!dragging) follow = true }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { if (!dragging) follow = !list.canScrollForward }
    }
    LaunchedEffect(shown) {
        if (follow && shown.isNotEmpty()) list.scrollToEnd(shown.lastIndex)
    }
}

/** Most rows fit at once; a row of the longest text may need a few more steps to its bottom. */
private const val MAX_STEPS_TO_THE_END = 8

/**
 * Scrolls to the true end: the bottom of the row at [lastIndex], not just its top, which is not
 * the end when the row is taller than the panel. So the panel's own jump always ends where the
 * position watcher in [FollowNewest] reads it as the end, and following holds.
 */
private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    var steps = 0
    while (canScrollForward && steps++ < MAX_STEPS_TO_THE_END) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        val rest = last.offset + last.size - info.viewportEndOffset
        if (rest <= 0) return
        scrollBy(rest.toFloat())
    }
}

/** One entry, read by TalkBack as one item: its level, its text and where it came from. */
@Composable
private fun EntryRow(entry: ConsoleEntry, modifier: Modifier = Modifier) {
    val style = entry.level.style()
    val level = entry.level.label()
    val location = entry.location()
    val description = if (location == null) {
        stringResource(R.string.preview_console_entry, level, entry.text)
    } else {
        stringResource(R.string.preview_console_entry_at, level, entry.text, location)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(style.container)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
    ) {
        Icon(
            imageVector = entry.level.icon,
            contentDescription = null,
            tint = style.icon,
            modifier = Modifier.padding(top = PhopencodeTheme.spacing.xxs).size(LEVEL_ICON_SIZE),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.text, style = PhopencodeTheme.codeTypography.small, color = style.content)
            if (location != null) {
                Text(text = location, style = MaterialTheme.typography.labelSmall, color = style.content)
            }
        }
    }
}
