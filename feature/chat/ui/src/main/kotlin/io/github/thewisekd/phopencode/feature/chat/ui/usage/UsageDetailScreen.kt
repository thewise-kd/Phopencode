package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.math.BigInteger
import java.util.Locale

private const val PERCENT = 100f

/**
 * The session's tokens and cost in full: input, output, reasoning, cache read and write, their total
 * and the cost, the model, and the context in use and its limit when known, all in the number format
 * of the screen's language. Draws [state] and sends [onIntent]: the top bar's back button sends
 * [UsageIntent.Close], and a failed model list shows an error whose retry sends [UsageIntent.Retry],
 * above the totals, which still show.
 *
 * Nothing animates: loading is a line of text, not a spinner. The root keeps the `placeholder:<route>`
 * tag the app's navigation test looks for.
 */
@Composable
fun UsageDetailScreen(
    route: ChatRoute.TokenUsage,
    state: UsageState,
    onIntent: (UsageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    PhopencodeScaffold(
        title = stringResource(R.string.chat_usage_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(UsageIntent.Close) },
    ) { padding ->
        // The inner padding sits on a child, not after verticalScroll: test scrolling measures the
        // viewport on the scrolling node's innermost bounds, which such padding would shrink.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Body(state, onIntent, Modifier.fillMaxWidth().padding(spacing.lg))
        }
    }
}

@Composable
private fun Body(state: UsageState, onIntent: (UsageIntent) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.md)) {
        val error = state.error
        if (error != null) {
            ErrorBanner(
                message = stringResource(R.string.chat_usage_error, stringResource(error.reasonRes())),
                onRetry = { onIntent(UsageIntent.Retry) },
            )
        }
        val locale = usageLocale()
        val usage = state.usage
        when {
            usage != null -> Counts(usage, state.model, locale)
            state.loading -> Text(
                text = stringResource(R.string.chat_usage_loading),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> EmptyState(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.chat_usage_empty_title),
                message = stringResource(R.string.chat_usage_empty_message),
            )
        }
        Context(state, locale)
    }
}

@Composable
private fun Counts(usage: TokenUsage, model: ModelRef?, locale: Locale) {
    Column {
        if (model != null) {
            UsageRow(R.string.chat_usage_model, "${model.providerId}/${model.modelId}", UsageTags.MODEL)
            HorizontalDivider()
        }
        UsageRow(R.string.chat_usage_input, formatCount(usage.input, locale), UsageTags.INPUT)
        UsageRow(R.string.chat_usage_output, formatCount(usage.output, locale), UsageTags.OUTPUT)
        UsageRow(R.string.chat_usage_reasoning, formatCount(usage.reasoning, locale), UsageTags.REASONING)
        UsageRow(R.string.chat_usage_cache_read, formatCount(usage.cacheRead, locale), UsageTags.CACHE_READ)
        UsageRow(R.string.chat_usage_cache_write, formatCount(usage.cacheWrite, locale), UsageTags.CACHE_WRITE)
        HorizontalDivider()
        UsageRow(R.string.chat_usage_total, formatCount(usage.totalTokens, locale), UsageTags.TOTAL)
        UsageRow(
            R.string.chat_usage_cost_label,
            costText(usage, locale) ?: stringResource(R.string.chat_usage_cost_unknown),
            UsageTags.COST,
        )
    }
}

/** The context in use, with its share and a bar when the limit is known, and the limit. */
@Composable
private fun Context(state: UsageState, locale: Locale) {
    val tokens: BigInteger? = state.contextTokens
    val limit = state.contextLimit
    val percent = state.contextPercent
    if (tokens == null && limit == null) return
    Column {
        HorizontalDivider()
        if (tokens != null) {
            val used = formatCount(tokens, locale)
            val value = if (percent != null) {
                stringResource(R.string.chat_usage_join, used, formatPercent(percent, locale))
            } else {
                used
            }
            UsageRow(R.string.chat_usage_context_used, value, UsageTags.CONTEXT_USED)
        }
        if (limit != null) {
            UsageRow(R.string.chat_usage_context_limit, formatCount(BigInteger.valueOf(limit), locale), UsageTags.CONTEXT_LIMIT)
        }
        if (percent != null) {
            val description = stringResource(R.string.chat_usage_context_progress, formatPercent(percent, locale))
            LinearProgressIndicator(
                progress = { percent.coerceIn(0, 100) / PERCENT },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PhopencodeTheme.spacing.sm)
                    .testTag(UsageTags.CONTEXT_PROGRESS)
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** A label and its value on one line, read as one. */
@Composable
private fun UsageRow(label: Int, value: String, tag: String) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
        )
    }
}
