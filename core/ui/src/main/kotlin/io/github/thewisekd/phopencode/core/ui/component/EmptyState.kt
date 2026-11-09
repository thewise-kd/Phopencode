package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.R
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

/** Marks the circle around an [EmptyState]'s icon, for tests. */
internal const val EMPTY_STATE_ICON_TAG = "ui-common:empty-state-icon"

private val IconCircle = 88.dp
private val IconSize = 40.dp
private val TextWidth = 360.dp

/** The one thing a user can do about an empty screen, such as adding the first item. */
@Immutable
data class EmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

/** The least height the space around an [EmptyState] asks of it, kept between two steps of one layout pass. */
private class Space {
    var minHeight: Int = 0
}

/**
 * Scrolls the content when it is taller than the space it is given, as with large text on a short
 * screen, and otherwise lets it take at least the space's least height, so it can center itself in it.
 *
 * A plain `verticalScroll` fails in a space without an end, inside another scrolling column: there the
 * content is held to its own height, which needs no scrolling. The outer step tells the inner one the
 * space's least height within the same layout pass, through [space].
 */
private fun Modifier.scrollWhenTaller(space: Space, scroll: ScrollState): Modifier = this
    .layout { measurable, constraints ->
        val bounded = if (constraints.hasBoundedHeight) {
            space.minHeight = constraints.minHeight
            constraints
        } else {
            space.minHeight = 0
            val height = measurable.maxIntrinsicHeight(constraints.maxWidth).coerceAtLeast(constraints.minHeight)
            constraints.copy(maxHeight = height)
        }
        val placeable = measurable.measure(bounded)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    .verticalScroll(scroll)
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = maxOf(constraints.minHeight, space.minHeight)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * What a screen shows when it has nothing to list. It centers its content in the
 * space [modifier] gives it, so a full screen passes `Modifier.fillMaxSize()`. When the
 * content is taller than that space, as with large text on a short screen, it scrolls.
 *
 * An [icon] is drawn large in a soft tinted circle above the text. It only decorates:
 * the text says what it means, so TalkBack does not read it. Without an icon the text
 * stands alone.
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.ui_common_empty_title),
    message: String? = null,
    icon: ImageVector? = null,
    action: EmptyStateAction? = null,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val space = remember { Space() }
    Column(
        modifier = modifier
            .scrollWhenTaller(space, rememberScrollState())
            .padding(spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Surface(
                modifier = Modifier.size(IconCircle).testTag(EMPTY_STATE_ICON_TAG),
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(IconSize))
                }
            }
            Spacer(Modifier.height(spacing.sm))
        }
        Text(
            text = title,
            modifier = Modifier.widthIn(max = TextWidth).semantics { heading() },
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.widthIn(max = TextWidth),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(spacing.sm))
            Button(onClick = action.onClick) {
                Text(action.label)
            }
        }
    }
}
