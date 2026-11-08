package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
private val MIN_TOUCH = 48.dp

/**
 * The usage line: the total tokens, the cost when one was reported, and the context percentage
 * when [UsageState.showsContext], such as "1,234 tokens · $0.12 · Context 7%", in the number format
 * of the screen's language, on one muted line. With a [title], the session's, as the chat's top bar
 * draws it, the title sits above it as the screen's heading, the two are one button, and the line
 * is shorter: the context and the cost, such as "Context 7% · $0.12", or the tokens when neither is
 * known.
 *
 * Draws [state]; a tap sends [UsageIntent.OpenDetail], in every state, so the page can show what is
 * known and any error. It is at least a full touch target tall.
 */
@Composable
fun UsageBar(
    state: UsageState,
    onIntent: (UsageIntent) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val spacing = PhopencodeTheme.spacing
    val openLabel = stringResource(R.string.chat_usage_bar_open)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = openLabel, role = Role.Button) { onIntent(UsageIntent.OpenDetail) }
            .padding(horizontal = spacing.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = barText(state, compact = title != null),
            color = colors.onSurfaceVariant,
            style = if (title != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The usage line. [compact], under the title, it keeps to what matters at a glance, the context
 * percentage then the cost, and names the tokens only when neither is known; the page has the rest.
 */
@Composable
private fun barText(state: UsageState, compact: Boolean): String {
    val usage = state.usage ?: return stringResource(
        if (state.loading) R.string.chat_usage_bar_loading else R.string.chat_usage_bar_empty,
    )
    val locale = usageLocale()
    val context = state.contextPercent?.let {
        stringResource(R.string.chat_usage_context_percent, formatPercent(it, locale))
    }
    val cost = costText(usage, locale)
    val parts = if (compact && (context != null || cost != null)) {
        listOfNotNull(context, cost)
    } else {
        listOfNotNull(tokensText(usage, locale), cost, context)
    }
    val join = stringResource(R.string.chat_usage_join)
    return parts.reduce { joined, next -> join.format(locale, joined, next) }
}
