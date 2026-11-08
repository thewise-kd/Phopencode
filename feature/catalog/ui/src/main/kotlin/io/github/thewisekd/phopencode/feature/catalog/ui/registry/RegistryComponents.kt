package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure

/**
 * Something loads. A still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests, which open these screens while the
 * presenter loads, would never return. TalkBack hears the same as for `Loading`: the message, an
 * indeterminate progress and a polite live region.
 */
@Composable
internal fun StillProgress(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A failure in place, with a retry when retrying can help. */
@Composable
internal fun FailureBanner(failure: CatalogFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    ErrorBanner(
        modifier = modifier,
        message = stringResource(failure.messageRes),
        onRetry = if (failure.canRetry) onRetry else null,
    )
}

/**
 * One row of a list as a rounded card, in the style of the session and server lists: a tap opens
 * it, which TalkBack announces as [onClickLabel]. At least a touch target tall, with a chevron at its
 * end that only decorates.
 */
@Composable
internal fun RowCard(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .clip(shape)
            .background(colors.surfaceContainer)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(start = spacing.lg, end = spacing.sm, top = spacing.md, bottom = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs), content = content)
        Icon(
            imageVector = PhopencodeIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
    }
}

/** A row's or a card's name. */
@Composable
internal fun RowTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Muted text under a row's name, such as its description. */
@Composable
internal fun RowDetail(text: String, maxLines: Int = 2) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A quiet tinted chip, such as an agent's mode. */
@Composable
internal fun SmallChip(text: String, modifier: Modifier = Modifier) {
    val spacing = PhopencodeTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The scrolling body of a detail screen: the failure above, then the progress while nothing is
 * there yet, or [content] once it is. Nothing to show after loading, with no failure, reads as not
 * found, which is what the presenter reports for a name the list lacks.
 */
@Composable
internal fun DetailBody(
    loading: Boolean,
    failure: CatalogFailure?,
    loadingMessage: String,
    hasContent: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        failure?.let { FailureBanner(it, onRetry) }
        when {
            hasContent -> content()
            loading -> StillProgress(loadingMessage, Modifier.fillMaxWidth())
            failure == null -> EmptyState(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(CatalogFailure.NotFound.messageRes),
                icon = PhopencodeIcons.Search,
            )
        }
    }
}

/** A titled group of a detail screen: a heading over a rounded card. */
@Composable
internal fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                content = content,
            )
        }
    }
}

/** The label's share of a [LabeledValue] row; the value never takes more than the rest. */
private const val LABEL_WEIGHT = 2f

/** The value's share of a [LabeledValue] row, at most: a short value leaves the rest to the label. */
private const val VALUE_WEIGHT = 3f

/**
 * A label and its value on one line, read by TalkBack as one. The value can come from the server
 * and be as long as it likes, such as a provider's name or a model's ID: it takes at most its share
 * of the row and wraps there, so the label always keeps room to be read.
 */
@Composable
internal fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(LABEL_WEIGHT),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(VALUE_WEIGHT, fill = false),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Plain text in a section's card. */
@Composable
internal fun BodyText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}
