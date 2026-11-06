package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import io.github.thewisekd.phopencode.feature.git.ui.R
import kotlin.math.roundToInt

/** Characters of a commit hash shown in the header, as `git log --oneline` shows them. */
private const val SHORT_HASH = 7

/**
 * The diff of one file. Draws [state] and sends [onIntent]; it decides nothing. Both
 * `GitRoute.FileDiff` and `GitRoute.CommitFileDiff` open it, so [route] is one of those; what the
 * screen says about the diff comes from [DiffState.target].
 *
 * - The top bar names the file; its back button sends [DiffIntent.Close] and Refresh
 *   [DiffIntent.Refresh].
 * - A header gives the path (and the old path of a rename or copy), the change kind, where the
 *   change is (working tree, staged, or a commit) and the counts of added and deleted lines.
 * - Each hunk starts with its `@@` header. Each line shows its old and new numbers in two gutters, a
 *   `+`, `-`, space or `\` marker and its text in a monospace font on a background by its kind. The
 *   marker carries the meaning as much as the colour, and each row tells TalkBack its kind, number
 *   and text.
 * - Long lines do not wrap: the text of every row scrolls sideways together, by one shared offset,
 *   while the gutters and markers stay in place.
 * - A binary file, a diff past the size limit and a diff without changed lines each say so. A failed
 *   read shows why, with Retry; while loading a line of text shows, and while the diff is read
 *   again the loaded one stays. Nothing animates without end.
 *
 * Paths and lines are drawn, never logged. The root keeps the `placeholder:<route>` tag the app's
 * navigation test looks for.
 */
@Composable
fun DiffScreen(
    route: GitRoute,
    state: DiffState,
    onIntent: (DiffIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = fileName(state.target.path),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(DiffIntent.Close) },
        actions = {
            TextButton(onClick = { onIntent(DiffIntent.Refresh) }, modifier = Modifier.testTag(DiffTags.REFRESH)) {
                Text(stringResource(R.string.git_diff_refresh))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Body(state, onIntent)
        }
    }
}

@Composable
private fun ColumnScope.Body(state: DiffState, onIntent: (DiffIntent) -> Unit) {
    val diff = state.diff
    val error = state.error
    if (error != null) {
        val spacing = PhopencodeTheme.spacing
        val banner = @Composable {
            ErrorBanner(
                modifier = Modifier.testTag(DiffTags.ERROR),
                message = stringResource(R.string.git_diff_error, failureReason(error)),
                onRetry = { onIntent(DiffIntent.Retry) },
            )
        }
        if (diff == null) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
            ) { banner() }
            return
        }
        Box(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) { banner() }
    }
    when {
        diff != null -> DiffBody(state.target, diff, Modifier.weight(1f).fillMaxWidth())
        else -> Text(
            text = stringResource(R.string.git_diff_loading),
            modifier = Modifier
                .fillMaxWidth()
                .padding(PhopencodeTheme.spacing.xl)
                .testTag(DiffTags.LOADING),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The header and the content, in one list that scrolls down and, for its lines, sideways. */
@Composable
private fun DiffBody(target: DiffTarget, diff: GitFileDiff, modifier: Modifier) {
    val code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val hunks = (diff.content as? DiffContent.Text)?.hunks.orEmpty()
    val scroll = rememberSideScroll(diff, hunks, code)
    val gutter = rememberGutterWidth(hunks, code)
    val scrollable = rememberScrollableState { delta -> scroll.scrollBy(delta) }
    Box(
        modifier
            .testTag(DiffTags.LINES)
            // As `horizontalScroll` does: a drag to the left moves the lines toward their ends. The
            // modifier also gives the box the semantics scroll action.
            .scrollable(scrollable, Orientation.Horizontal, reverseDirection = true)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(
                    value = { scroll.offset },
                    maxValue = { scroll.max.toFloat() },
                )
            },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(DiffTags.LIST),
            state = rememberLazyListState(),
        ) {
            item(key = "header") { Header(target, diff) }
            content(diff.content, hunks, scroll, gutter, code)
        }
    }
}

private fun LazyListScope.content(
    content: DiffContent,
    hunks: List<DiffHunk>,
    scroll: SideScroll,
    gutter: GutterWidth,
    code: TextStyle,
) {
    when (content) {
        is DiffContent.Text -> hunks.forEachIndexed { h, hunk ->
            item(key = "hunk:$h", contentType = "hunk") { HunkHeader(h, hunk, code) }
            itemsIndexedLines(h, hunk.lines, scroll, gutter, code)
        }
        DiffContent.Binary -> item(key = "binary") {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(DiffTags.BINARY),
                title = stringResource(R.string.git_diff_binary_title),
                message = stringResource(R.string.git_diff_binary_message),
            )
        }
        is DiffContent.TooLarge -> item(key = "too-large") {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(DiffTags.TOO_LARGE),
                title = stringResource(R.string.git_diff_too_large_title),
                message = stringResource(R.string.git_diff_too_large_message, formatSize(content.limitBytes)),
            )
        }
        DiffContent.Unchanged -> item(key = "unchanged") {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(DiffTags.UNCHANGED),
                title = stringResource(R.string.git_diff_unchanged_title),
                message = stringResource(R.string.git_diff_unchanged_message),
            )
        }
    }
}

private fun LazyListScope.itemsIndexedLines(
    hunk: Int,
    lines: List<DiffLine>,
    scroll: SideScroll,
    gutter: GutterWidth,
    code: TextStyle,
) {
    lines.forEachIndexed { l, line ->
        item(key = "line:$hunk:$l", contentType = "line") { LineRow(hunk, l, line, scroll, gutter, code) }
    }
}

@Composable
private fun Header(target: DiffTarget, diff: GitFileDiff) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = diff.path,
            modifier = Modifier.testTag(DiffTags.PATH),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
        diff.oldPath?.let { old ->
            Text(
                text = stringResource(R.string.git_diff_old_path, old),
                modifier = Modifier.testTag(DiffTags.OLD_PATH),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(diff.kind.labelRes()),
                modifier = Modifier.testTag(DiffTags.KIND),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = contextLabel(target),
                modifier = Modifier.testTag(DiffTags.CONTEXT),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Counts(diff.additions, diff.deletions)
        }
    }
}

/** `+3 -2`, coloured, and read out as words since a sign alone says little. */
@Composable
private fun Counts(additions: Int, deletions: Int) {
    val status = PhopencodeTheme.status
    val description = stringResource(
        R.string.git_diff_counts,
        pluralStringResource(R.plurals.git_diff_additions, additions, additions),
        pluralStringResource(R.plurals.git_diff_deletions, deletions, deletions),
    )
    Row(
        modifier = Modifier
            .testTag(DiffTags.COUNTS)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
    ) {
        Text(text = "+$additions", color = status.success.color, style = MaterialTheme.typography.labelMedium)
        Text(text = "-$deletions", color = status.danger.color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun contextLabel(target: DiffTarget): String = when (target) {
    is DiffTarget.Working -> stringResource(R.string.git_diff_context_working)
    is DiffTarget.Staged -> stringResource(R.string.git_diff_context_staged)
    is DiffTarget.InCommit -> stringResource(R.string.git_diff_context_commit, target.commit.take(SHORT_HASH))
}

@Composable
private fun HunkHeader(index: Int, hunk: DiffHunk, code: TextStyle) {
    val spacing = PhopencodeTheme.spacing
    val description = stringResource(R.string.git_diff_hunk, hunk.newStart, hunk.oldStart)
    val header = buildString {
        append("@@ -").append(hunk.oldStart).append(',').append(hunk.oldCount)
        append(" +").append(hunk.newStart).append(',').append(hunk.newCount).append(" @@")
        hunk.section?.let { append(' ').append(it) }
    }
    Text(
        text = header,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DiffTags.hunk(index))
            .semantics(mergeDescendants = true) { contentDescription = description }
            .background(PhopencodeTheme.status.info.container)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        color = PhopencodeTheme.status.info.onContainer,
        style = code,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Which colours a line kind draws with; read from the theme, so they fit light and dark. */
private data class LineColors(
    val background: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)

@Composable
private fun lineColors(kind: DiffLineKind): LineColors {
    val status = PhopencodeTheme.status
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        DiffLineKind.Added -> LineColors(status.success.container, status.success.onContainer)
        DiffLineKind.Deleted -> LineColors(status.danger.container, status.danger.onContainer)
        DiffLineKind.Context -> LineColors(scheme.surface, scheme.onSurface)
        DiffLineKind.NoNewline -> LineColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun LineRow(
    hunk: Int,
    index: Int,
    line: DiffLine,
    scroll: SideScroll,
    gutter: GutterWidth,
    code: TextStyle,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = lineColors(line.kind)
    val noNewline = stringResource(R.string.git_diff_line_no_newline)
    val text = if (line.kind == DiffLineKind.NoNewline) noNewline else displayText(line.text)
    val description = lineDescription(line, noNewline)
    val style = if (line.kind == DiffLineKind.NoNewline) code.copy(fontStyle = FontStyle.Italic) else code
    // Code reads left to right whatever the app's language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(DiffTags.line(hunk, index))
                .semantics(mergeDescendants = true) { contentDescription = description }
                .background(colors.background),
        ) {
            Gutter(line.oldNumber, gutter, colors, style, DiffTags.OLD_NUMBER)
            Gutter(line.newNumber, gutter, colors, style, DiffTags.NEW_NUMBER)
            Text(
                text = line.kind.marker(),
                modifier = Modifier
                    .padding(horizontal = spacing.xs)
                    .testTag(DiffTags.MARKER),
                color = colors.content,
                style = style,
            )
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                        val width = constraints.maxWidth
                        scroll.measured(viewport = width, content = placeable.width)
                        layout(width, placeable.height) {
                            placeable.place(-scroll.offset.roundToInt(), 0)
                        }
                    },
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(end = spacing.sm).testTag(DiffTags.TEXT),
                    color = colors.content,
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Gutter(number: Int?, width: GutterWidth, colors: LineColors, style: TextStyle, tag: String) {
    Text(
        text = number?.toString().orEmpty(),
        modifier = Modifier
            .width(width.dp)
            .background(colors.content.copy(alpha = GUTTER_TINT))
            .padding(horizontal = PhopencodeTheme.spacing.xs)
            .testTag(tag),
        color = colors.content.copy(alpha = GUTTER_TEXT_ALPHA),
        style = style,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

private const val GUTTER_TINT = 0.06f
private const val GUTTER_TEXT_ALPHA = 0.75f

@Composable
private fun lineDescription(line: DiffLine, noNewline: String): String {
    val text = line.text.ifBlank { stringResource(R.string.git_diff_line_blank) }
    return when (line.kind) {
        DiffLineKind.Added -> stringResource(R.string.git_diff_line_added, line.newNumber ?: 0, text)
        DiffLineKind.Deleted -> stringResource(R.string.git_diff_line_deleted, line.oldNumber ?: 0, text)
        DiffLineKind.Context -> stringResource(R.string.git_diff_line_context, line.newNumber ?: 0, text)
        DiffLineKind.NoNewline -> noNewline
    }
}

private fun DiffLineKind.marker(): String = when (this) {
    DiffLineKind.Added -> "+"
    DiffLineKind.Deleted -> "-"
    DiffLineKind.Context -> " "
    DiffLineKind.NoNewline -> "\\"
}

/** Tabs become spaces, since a tab has no fixed width in a text field. */
private fun displayText(text: String): String = text.replace("\t", "    ")

/** The width of a line-number gutter, wide enough for the largest number in the diff. */
private class GutterWidth(val dp: androidx.compose.ui.unit.Dp)

@Composable
private fun rememberGutterWidth(hunks: List<DiffHunk>, code: TextStyle): GutterWidth {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val padding = PhopencodeTheme.spacing.xs
    val digits = remember(hunks) {
        val largest = hunks.maxOfOrNull { hunk ->
            hunk.lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) } ?: 0
        } ?: 0
        largest.toString().length
    }
    return remember(digits, code, density, padding) {
        val width = measurer.measure("0".repeat(digits), code).size.width
        GutterWidth(with(density) { width.toDp() } + padding * 2)
    }
}
