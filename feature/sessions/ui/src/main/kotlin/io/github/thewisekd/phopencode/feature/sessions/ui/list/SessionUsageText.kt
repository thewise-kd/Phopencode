package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.feature.sessions.ui.R
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** The smallest amount shown: one cent. */
private val CENT = BigDecimal("0.01")

private val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())

/**
 * A row's tokens and cost, such as "1,234 tokens · $0.12", in the number format of the screen's
 * language. The cost is left out when no step reported one. When some steps reported none, the
 * amount is a lower bound and says so. An amount below a cent is never rounded to zero: it reads
 * "under $0.01", or "more than $0.00" when it is only a lower bound.
 */
@Composable
internal fun usageText(usage: TokenUsage): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val total = usage.totalTokens
    val tokens = pluralStringResource(
        R.plurals.sessions_list_tokens,
        total.min(INT_MAX).toInt(),
        NumberFormat.getIntegerInstance(locale).format(total),
    )
    val cost = usage.cost?.amount ?: return tokens
    val shown = when {
        // A known spend below a cent, plus some unknown: rounding down to "at least $0.00" would hide it.
        usage.hasMissingCost && cost.signum() > 0 && cost < CENT ->
            stringResource(R.string.sessions_list_cost_more_than, money(BigDecimal.ZERO, locale, RoundingMode.DOWN))
        usage.hasMissingCost ->
            stringResource(R.string.sessions_list_cost_at_least, money(cost, locale, RoundingMode.DOWN))
        cost.signum() > 0 && cost < CENT ->
            stringResource(R.string.sessions_list_cost_under, money(CENT, locale, RoundingMode.HALF_UP))
        else -> stringResource(R.string.sessions_list_cost, money(cost, locale, RoundingMode.HALF_UP))
    }
    return stringResource(R.string.sessions_list_usage, tokens, shown)
}

/** Two decimals with the language's separators; the currency sign comes from the string resource. */
private fun money(amount: BigDecimal, locale: Locale, rounding: RoundingMode): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = rounding
    }.format(amount)
