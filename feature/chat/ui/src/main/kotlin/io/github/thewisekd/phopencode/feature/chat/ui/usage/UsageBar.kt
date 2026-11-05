package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
private val MIN_TOUCH = 48.dp

/**
 * The compact bar at the top of the chat: the total tokens, the cost when one was reported, and the
 * context percentage when [UsageState.showsContext], such as "1,234 tokens · $0.12 · Context 7%", in
 * the number format of the screen's language. Draws [state]; a tap sends [UsageIntent.OpenDetail], in
 * every state, so the page can show what is known and any error.
 */
@Composable
fun UsageBar(
    state: UsageState,
    onIntent: (UsageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val openLabel = stringResource(R.string.chat_usage_bar_open)
    Surface(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clickable(onClickLabel = openLabel, role = Role.Button) { onIntent(UsageIntent.OpenDetail) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.heightIn(min = MIN_TOUCH).padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = barText(state),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun barText(state: UsageState): String {
    val usage = state.usage ?: return stringResource(
        if (state.loading) R.string.chat_usage_bar_loading else R.string.chat_usage_bar_empty,
    )
    val locale = usageLocale()
    val parts = buildList {
        add(tokensText(usage, locale))
        costText(usage, locale)?.let(::add)
        state.contextPercent?.let {
            add(stringResource(R.string.chat_usage_context_percent, formatPercent(it, locale)))
        }
    }
    val join = stringResource(R.string.chat_usage_join)
    return parts.reduce { joined, next -> join.format(locale, joined, next) }
}
