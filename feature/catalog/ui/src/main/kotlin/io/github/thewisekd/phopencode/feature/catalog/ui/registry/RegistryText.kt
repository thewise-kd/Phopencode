package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PriceThreshold
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.ui.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** The most decimals a price shows: a millionth of a dollar per million tokens. */
private const val PRICE_MAX_DECIMALS = 6

private const val PRICE_MIN_DECIMALS = 2

/** The smallest price shown as a number; anything above zero and below it reads `<0.000001`. */
private val SMALLEST_PRICE: BigDecimal = BigDecimal.ONE.movePointLeft(PRICE_MAX_DECIMALS)

/** A mark, not a word: "less than", the same in both languages. */
private const val LESS_THAN = "<"

/**
 * The screen's language, from the composition's configuration so that a language change redraws
 * the numbers; the process default is not observable. An empty locale list, which Android does not
 * give, falls back to the root locale.
 */
@Composable
@ReadOnlyComposable
internal fun screenLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** A token count grouped the way [locale] groups digits, such as "200,000" or "200.000". */
internal fun formatTokens(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/**
 * A price per million tokens, without its currency sign: two decimals at least, so "3.00", and up
 * to six for the small prices of cache reads, so "0.075". A price above zero that six decimals would
 * round to nothing reads "<0.000001", never as free.
 */
internal fun formatPrice(cost: Cost, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = PRICE_MIN_DECIMALS
        maximumFractionDigits = PRICE_MAX_DECIMALS
        roundingMode = RoundingMode.HALF_UP
    }
    val amount = cost.amount
    if (amount.signum() > 0 && amount < SMALLEST_PRICE) return LESS_THAN + format.format(SMALLEST_PRICE)
    return format.format(amount)
}

/** "200,000 tokens", in the screen's language. A count past `Int` still picks the "other" form. */
@Composable
internal fun tokensText(value: Long): String = pluralStringResource(
    R.plurals.catalog_registry_tokens,
    value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    formatTokens(value, screenLocale()),
)

@Composable
internal fun priceText(cost: Cost?): String = if (cost == null) {
    stringResource(R.string.catalog_registry_price_unknown)
} else {
    stringResource(R.string.catalog_registry_price, formatPrice(cost, screenLocale()))
}

@Composable
internal fun tierText(threshold: PriceThreshold?): String {
    if (threshold == null) return stringResource(R.string.catalog_registry_tier_base)
    val size = formatTokens(threshold.size, screenLocale())
    return if (threshold.type == CONTEXT_TIER) {
        stringResource(R.string.catalog_registry_tier_context, size)
    } else {
        stringResource(R.string.catalog_registry_tier_other, threshold.type, size)
    }
}

/** The only tier kind the server's spec names today. */
private const val CONTEXT_TIER = "context"

@Composable
internal fun modeText(mode: AgentMode): String = when (mode) {
    AgentMode.Primary -> stringResource(R.string.catalog_registry_mode_primary)
    AgentMode.Subagent -> stringResource(R.string.catalog_registry_mode_subagent)
    AgentMode.All -> stringResource(R.string.catalog_registry_mode_all)
    is AgentMode.Unknown -> stringResource(R.string.catalog_registry_mode_unknown, mode.raw)
}

@Composable
internal fun effectText(effect: PermissionEffect): String = when (effect) {
    PermissionEffect.Allow -> stringResource(R.string.catalog_registry_permission_allow)
    PermissionEffect.Deny -> stringResource(R.string.catalog_registry_permission_deny)
    PermissionEffect.Ask -> stringResource(R.string.catalog_registry_permission_ask)
    is PermissionEffect.Unknown -> stringResource(R.string.catalog_registry_permission_unknown, effect.raw)
}

/** How the app names a model: its provider's ID and its own, as the server's configuration does. */
internal fun ModelRef.label(): String = "$providerId/$modelId"

/** Reading the list again cannot fix a lost connection or refused credentials. */
internal val CatalogFailure.canRetry: Boolean
    get() = when (this) {
        CatalogFailure.NotConnected, CatalogFailure.Unauthorized -> false
        CatalogFailure.Unreachable, CatalogFailure.NotFound, CatalogFailure.Rejected,
        CatalogFailure.ServerFailed, CatalogFailure.Unknown,
        -> true
    }

@get:StringRes
internal val CatalogFailure.messageRes: Int
    get() = when (this) {
        CatalogFailure.NotConnected -> R.string.catalog_registry_error_not_connected
        CatalogFailure.Unreachable -> R.string.catalog_registry_error_unreachable
        CatalogFailure.Unauthorized -> R.string.catalog_registry_error_unauthorized
        CatalogFailure.NotFound -> R.string.catalog_registry_error_not_found
        CatalogFailure.Rejected -> R.string.catalog_registry_error_rejected
        CatalogFailure.ServerFailed -> R.string.catalog_registry_error_server
        CatalogFailure.Unknown -> R.string.catalog_registry_error_unknown
    }
