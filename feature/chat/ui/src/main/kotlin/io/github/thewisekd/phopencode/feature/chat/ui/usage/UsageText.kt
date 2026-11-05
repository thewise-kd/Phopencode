package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** The smallest amount shown: one cent. */
private val CENT = BigDecimal("0.01")

private val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())

private const val PERCENT = 100.0

/**
 * The language the screen is drawn in, which sets the number format.
 *
 * Read from the composition's configuration, so a language change redraws it; the process default is
 * not observable. An empty locale list, which Android does not give, falls back to the root locale.
 */
@Composable
internal fun usageLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** A count with the language's grouping, such as "1,234" in English and "1.234" in Turkish. */
internal fun formatCount(value: BigInteger, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/** A count of 0 to 100 as the language writes a percentage, such as "7%" in English and "%7" in Turkish. */
internal fun formatPercent(percent: Int, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).format(percent / PERCENT)

/** The total tokens, such as "1,234 tokens". */
@Composable
internal fun tokensText(usage: TokenUsage, locale: Locale): String {
    val total = usage.totalTokens
    return pluralStringResource(R.plurals.chat_usage_tokens, total.min(INT_MAX).toInt(), formatCount(total, locale))
}

/**
 * The cost, such as "$0.12", in the language's number format; null when no step reported one. When
 * some steps reported none, the amount is a lower bound and says so. An amount below a cent is never
 * rounded to zero: it reads "under $0.01", or "more than $0.00" when it is only a lower bound.
 */
@Composable
internal fun costText(usage: TokenUsage, locale: Locale): String? {
    val cost = usage.cost?.amount ?: return null
    return when {
        // A known spend below a cent, plus some unknown: rounding down to "at least $0.00" would hide it.
        usage.hasMissingCost && cost.signum() > 0 && cost < CENT ->
            stringResource(R.string.chat_usage_cost_more_than, money(BigDecimal.ZERO, locale, RoundingMode.DOWN))
        usage.hasMissingCost ->
            stringResource(R.string.chat_usage_cost_at_least, money(cost, locale, RoundingMode.DOWN))
        cost.signum() > 0 && cost < CENT ->
            stringResource(R.string.chat_usage_cost_under, money(CENT, locale, RoundingMode.HALF_UP))
        else -> stringResource(R.string.chat_usage_cost, money(cost, locale, RoundingMode.HALF_UP))
    }
}

/** Two decimals with the language's separators; the currency sign comes from the string resource. */
private fun money(amount: BigDecimal, locale: Locale, rounding: RoundingMode): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = rounding
    }.format(amount)

/** Why the model list could not be loaded, as a sentence. */
@StringRes
internal fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_usage_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_usage_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_usage_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_usage_reason_not_found
    ChatFailure.Conflict -> R.string.chat_usage_reason_conflict
    ChatFailure.Rejected -> R.string.chat_usage_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_usage_reason_server
    ChatFailure.Unknown -> R.string.chat_usage_reason_unknown
}
