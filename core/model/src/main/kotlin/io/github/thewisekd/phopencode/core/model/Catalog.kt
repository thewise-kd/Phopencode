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

/** @property permissions The agent's rules, in the server's own shape. */
data class Agent(
    val name: String,
    val description: String? = null,
    val mode: AgentMode,
    val hidden: Boolean = false,
    val model: ModelRef? = null,
    val permissions: DataValue? = null,
)

sealed interface AgentMode {
    data object Primary : AgentMode

    data object Subagent : AgentMode

    data object All : AgentMode

    data class Unknown(val raw: String) : AgentMode
}

/** @property variants Named variants of the model, if it has any. */
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

/** Prices as the server reports them. Null means unknown, which is not the same as free. */
data class ModelPricing(
    val input: Cost? = null,
    val output: Cost? = null,
    val reasoning: Cost? = null,
    val cacheRead: Cost? = null,
    val cacheWrite: Cost? = null,
)

/** Points at a model, as a session or an agent does. */
data class ModelRef(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)
