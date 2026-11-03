package io.github.thewisekd.phopencode.core.api_http.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonPrimitive

/*
 * The server's catalog shapes, as recorded (`docs/api/README.md`, "Catalog") and specified in
 * `openapi.json`. Only what the core types hold is declared; `ApiJson` drops the rest.
 *
 * Providers, models, model variants and agents (in `request`) carry `settings`, `headers` and
 * `body`, with API keys in clear; MCP servers may carry environment or OAuth details. No type here
 * may declare any of them: what is never decoded cannot be kept or logged. Numbers are kept as their
 * JSON text and checked in CatalogMapping.kt before any arithmetic.
 */

/** `Skill.Info`. */
@Serializable
internal class SkillWire(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("autoinvoke") val autoInvoke: Boolean = false,
    val path: String,
    val content: String,
)

/** `Command.Info`. */
@Serializable
internal class CommandWire(
    val name: String,
    val description: String? = null,
)

/** `Agent.Info`, without `request` (provider settings and headers) or `system` (the prompt). */
@Serializable
internal class AgentWire(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: String,
    val hidden: Boolean = false,
    val model: AgentModelWire? = null,
    val permissions: List<RuleWire> = emptyList(),
)

/** `Model.Ref`. */
@Serializable
internal class AgentModelWire(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val variant: String? = null,
)

/** `Permission.Rule`. */
@Serializable
internal class RuleWire(
    val action: String,
    val resource: String,
    val effect: String,
)

/**
 * `Model.Info`. `id` is what a `Model.Ref` names, so it is the model's ID here; `modelID` is the
 * provider's own name for it and is not kept. The parts the spec requires but the core type does not
 * need are optional, so that their absence costs no model.
 */
@Serializable
internal class ModelWire(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val name: String,
    val limit: LimitWire? = null,
    val cost: List<PriceTierWire>? = null,
    val variants: List<VariantWire> = emptyList(),
)

@Serializable
internal class LimitWire(
    val context: JsonPrimitive? = null,
    val input: JsonPrimitive? = null,
    val output: JsonPrimitive? = null,
)

/** `Model.Cost`: USD per million tokens. */
@Serializable
internal class PriceTierWire(
    val tier: ThresholdWire? = null,
    val input: JsonPrimitive? = null,
    val output: JsonPrimitive? = null,
    val cache: CachePriceWire? = null,
)

@Serializable
internal class ThresholdWire(
    val type: String,
    val size: JsonPrimitive? = null,
)

@Serializable
internal class CachePriceWire(
    val read: JsonPrimitive? = null,
    val write: JsonPrimitive? = null,
)

/** `Model.Variant`, by ID alone: its `settings`, `headers` and `body` are dropped. */
@Serializable
internal class VariantWire(val id: String)

/** `Provider.Info`, by ID and name alone. */
@Serializable
internal class ProviderWire(
    val id: String,
    val name: String,
)

/** `Mcp.Server`. */
@Serializable
internal class McpServerWire(
    val name: String,
    val status: McpStatusWire,
)

/** `Mcp.Status.*`: `error` comes with `failed` and `needs_auth`. */
@Serializable
internal class McpStatusWire(
    val status: String,
    val error: String? = null,
)

/** `Plugin.Info`. `id` is optional in the spec. */
@Serializable
internal class PluginWire(
    val id: String? = null,
    val state: PluginStateWire,
)

/** `Plugin.State`: `error` comes with `failed`. Its `ref` may name a package address and is dropped. */
@Serializable
internal class PluginStateWire(
    val status: String,
    val error: String? = null,
)

/** Every wire type of this package, for the test that no secret-bearing field is declared. */
internal object CatalogWireTypes {
    val all: List<SerialDescriptor> = listOf(
        SkillWire.serializer().descriptor,
        CommandWire.serializer().descriptor,
        AgentWire.serializer().descriptor,
        ModelWire.serializer().descriptor,
        ProviderWire.serializer().descriptor,
        McpServerWire.serializer().descriptor,
        PluginWire.serializer().descriptor,
    )
}
