package io.github.thewisekd.phopencode.core.model

/** @property content The full text. Can be large, so a skill list is fetched once and cached. */
data class Skill(
    val id: String,
    val name: String,
    val description: String? = null,
    val autoInvoke: Boolean = false,
    val path: String,
    val content: String,
)

data class Command(
    val name: String,
    val description: String? = null,
)

/**
 * @property id What the API takes, for example `build`.
 * @property name The display form, for example `Build`.
 * @property hidden Internal agents such as `compaction` and `title` are listed too: filter on this.
 */
data class Agent(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: AgentMode,
    val hidden: Boolean = false,
    val model: ModelRef? = null,
    val permissions: List<PermissionRule> = emptyList(),
)

sealed interface AgentMode {
    data object Primary : AgentMode

    data object Subagent : AgentMode

    data object All : AgentMode

    data class Unknown(val raw: String) : AgentMode
}

/**
 * A model. The server sends `settings` and `headers` in clear, API key included, so a decoder must drop
 * them and this type has no place for them. A variant is kept by ID alone for the same reason.
 *
 * @property variants Named variants of the model, if it has any.
 */
data class Model(
    val id: String,
    val providerId: String,
    val name: String,
    val limit: ModelLimit = ModelLimit(),
    val pricing: ModelPricing? = null,
    val variants: List<String> = emptyList(),
)

/** Token limits. Null means the server gave none, which is not the same as a limit of zero. */
data class ModelLimit(
    val context: Long? = null,
    val input: Long? = null,
    val output: Long? = null,
)

/** A model's `cost` list. All prices are in USD per million tokens. */
data class ModelPricing(val tiers: List<ModelPriceTier>) {
    /** The tier with no condition, if there is one. */
    val base: ModelPriceTier?
        get() = tiers.firstOrNull { it.threshold == null }
}

/**
 * The prices of one tier. There is no reasoning price: the server bills reasoning as output.
 *
 * @property threshold The server's `tier`; null on a tier that applies with no condition.
 * @property cacheRead Null means unknown, which is not the same as free.
 * @property cacheWrite Null means unknown, which is not the same as free.
 */
data class ModelPriceTier(
    val threshold: PriceThreshold? = null,
    val input: Cost,
    val output: Cost,
    val cacheRead: Cost? = null,
    val cacheWrite: Cost? = null,
) {
    /**
     * What [usage] costs at these prices, with the server's formula. Null when it cannot be known, as
     * for cache tokens with no cache price or an amount outside the range of [Cost].
     *
     * [TokenUsage.input] already leaves out cached tokens, and [TokenUsage.output] reasoning tokens.
     */
    fun costOf(usage: TokenUsage): Cost? {
        return Cost.totalPerMillionTokens(
            listOf(
                input to usage.input,
                output to usage.output + usage.reasoning,
                cacheRead to usage.cacheRead,
                cacheWrite to usage.cacheWrite,
            ),
        )
    }
}

/** @property type The server's `tier.type`; `context` is the only one in the spec today. */
data class PriceThreshold(
    val type: String,
    val size: Long,
)

/** Points at a model, as a session or an agent does. */
data class ModelRef(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)
