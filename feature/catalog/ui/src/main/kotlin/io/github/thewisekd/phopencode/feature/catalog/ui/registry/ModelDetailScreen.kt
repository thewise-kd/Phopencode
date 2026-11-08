package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelPriceTier
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * One model: its provider and ID, whether it is the server's default, its token limits, its prices
 * per tier and its variants. Draws [state] and sends [onIntent]; it decides nothing itself. The title
 * is the model's ID from the route until the model is read, then its display name.
 *
 * Numbers follow the screen's language. A limit or a price the server did not give says so, never
 * zero: unknown is not free.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ModelDetailScreen(
    route: CatalogRoute.ModelDetail,
    state: ModelDetailState,
    onIntent: (ModelDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.model
    PhopencodeScaffold(
        title = model?.name ?: state.modelId,
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(ModelDetailIntent.Close) },
    ) { padding ->
        DetailBody(
            loading = state.loading,
            failure = state.error,
            loadingMessage = stringResource(R.string.catalog_registry_loading_model),
            hasContent = model != null,
            onRetry = { onIntent(ModelDetailIntent.Retry) },
            modifier = Modifier.padding(padding),
        ) {
            if (model != null) ModelDetails(model, state)
        }
    }
}

@Composable
private fun ModelDetails(model: Model, state: ModelDetailState) {
    Section(stringResource(R.string.catalog_registry_section_model)) {
        LabeledValue(stringResource(R.string.catalog_registry_provider), state.providerName ?: model.providerId)
        LabeledValue(stringResource(R.string.catalog_registry_model_id), model.id)
        if (state.isDefault) {
            StatusChip(status = StatusTone.Success, label = stringResource(R.string.catalog_registry_default))
        }
    }
    Section(stringResource(R.string.catalog_registry_section_limits)) {
        LimitRow(R.string.catalog_registry_limit_context, model.limit.context)
        LimitRow(R.string.catalog_registry_limit_input, model.limit.input)
        LimitRow(R.string.catalog_registry_limit_output, model.limit.output)
    }
    Section(stringResource(R.string.catalog_registry_section_pricing)) {
        val tiers = model.pricing?.tiers.orEmpty()
        if (tiers.isEmpty()) {
            BodyText(stringResource(R.string.catalog_registry_no_pricing), muted = true)
        } else {
            BodyText(stringResource(R.string.catalog_registry_pricing_unit), muted = true)
            tiers.forEach { tier ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TierPrices(tier)
            }
        }
    }
    if (model.variants.isNotEmpty()) {
        Section(stringResource(R.string.catalog_registry_section_variants)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
            ) {
                model.variants.forEach { SmallChip(it) }
            }
        }
    }
}

@Composable
private fun LimitRow(label: Int, value: Long?) {
    LabeledValue(
        label = stringResource(label),
        value = value?.let { tokensText(it) } ?: stringResource(R.string.catalog_registry_not_given),
    )
}

/** One tier: when it applies, then its four prices. */
@Composable
private fun TierPrices(tier: ModelPriceTier) {
    Text(
        text = tierText(tier.threshold),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
    )
    LabeledValue(stringResource(R.string.catalog_registry_price_input), priceText(tier.input))
    LabeledValue(stringResource(R.string.catalog_registry_price_output), priceText(tier.output))
    LabeledValue(stringResource(R.string.catalog_registry_price_cache_read), priceText(tier.cacheRead))
    LabeledValue(stringResource(R.string.catalog_registry_price_cache_write), priceText(tier.cacheWrite))
}
