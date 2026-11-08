package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishRegistryTest : RegistryLanguageContract() {
    override val strings = ENGLISH_REGISTRY_STRINGS
}

internal val ENGLISH_REGISTRY_STRINGS = ExpectedRegistryStrings(
    title = "Commands, agents, models",
    tabs = mapOf(RegistryTab.Commands to "Commands", RegistryTab.Agents to "Agents", RegistryTab.Models to "Models"),
    reload = "Reload this list",
    loading = mapOf(
        RegistryTab.Commands to "Loading commands…",
        RegistryTab.Agents to "Loading agents…",
        RegistryTab.Models to "Loading models…",
    ),
    reloading = "Reloading…",
    emptyTitles = mapOf(
        RegistryTab.Commands to "No commands",
        RegistryTab.Agents to "No agents",
        RegistryTab.Models to "No models",
    ),
    emptyMessages = mapOf(
        RegistryTab.Commands to "This server has no custom commands.",
        RegistryTab.Agents to "This server lists no agents.",
        RegistryTab.Models to "No provider on this server offers a model.",
    ),
    noDescription = "No description",
    modes = listOf("Primary", "Subagent", "Primary and subagent"),
    unknownMode = "Mode: sideways",
    hidden = "Hidden",
    default = "Default",
    contextLine = "Context: 200,000 tokens",
    loadingCommand = "Loading the command…",
    loadingAgent = "Loading the agent…",
    loadingModel = "Loading the model…",
    description = "Description",
    mode = "Mode",
    model = "Model",
    permissions = "Permissions",
    noPermissions = "No permission rules",
    sessionModel = "Uses the session's model",
    effects = listOf("Allow", "Deny", "Ask", "Unknown: maybe"),
    provider = "Provider",
    modelId = "Model ID",
    limits = "Token limits",
    limitNames = listOf("Context", "Input", "Output"),
    limitValues = listOf("200,000 tokens", "64,000 tokens"),
    notGiven = "Not given",
    pricing = "Pricing",
    pricingUnit = "USD per million tokens",
    baseTier = "Standard",
    contextTier = "Above 200,000 context tokens",
    priceNames = listOf("Input", "Output", "Cache read", "Cache write"),
    prices = listOf("$3.00", "$15.00", "$0.075", "$6.00", "$22.50"),
    unknownPrice = "Unknown",
    noPricing = "The server gives no prices for this model.",
    variants = "Variants",
    errors = mapOf(
        CatalogFailure.NotConnected to "Not connected to the server. Go back and connect again.",
        CatalogFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
        CatalogFailure.Unauthorized to "The server refused the credentials.",
        CatalogFailure.NotFound to "Not found on the server. It may have been removed from its configuration.",
        CatalogFailure.Rejected to "The server refused the request.",
        CatalogFailure.ServerFailed to "The server ran into an error.",
        CatalogFailure.Unknown to "Something went wrong.",
    ),
    retry = "Try again",
)
