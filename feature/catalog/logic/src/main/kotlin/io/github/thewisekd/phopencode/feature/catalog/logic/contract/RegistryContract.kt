package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef

enum class RegistryTab {
    Commands,
    Agents,
    Models,
}

/**
 * The server's commands, agents and models, `CatalogRoute.Registry` (P10.4 logic, P10.5 screen): one
 * tab each; a row opens its details.
 *
 * Where it comes from: [RegistryCatalog], each list on its own, so one that fails shows its error on
 * its tab and leaves the others. Going back to the screen reads from there again
 * ([RegistryIntent.Refresh]).
 *
 * @property agents Without the ones the server marks hidden (`compaction`, `title` and the like), in
 *   the server's order.
 * @property models Every model, in the server's order.
 * @property providers For the models' provider names.
 * @property defaultModel The server's default model, marked in [models]; null when it has none.
 */
data class RegistryState(
    val tab: RegistryTab = RegistryTab.Commands,
    val commands: CatalogList<Command> = CatalogList(),
    val agents: CatalogList<Agent> = CatalogList(),
    val models: CatalogList<Model> = CatalogList(),
    val providers: List<Provider> = emptyList(),
    val defaultModel: ModelRef? = null,
) {
    /** The lists print counts only: a description or a permission's resource is the user's. */
    override fun toString(): String =
        "RegistryState(tab=$tab, commands=$commands, agents=$agents, models=$models, providers=${providers.size}, " +
            "defaultModel=$defaultModel)"
}

sealed interface RegistryIntent {
    data class SelectTab(val tab: RegistryTab) : RegistryIntent

    /** Reads through [RegistryCatalog] again, kept lists first; the graph sends it when the screen is shown again. */
    data object Refresh : RegistryIntent

    /** Reads [tab]'s list from the server again, as a pull to refresh or a retry after its error. */
    data class Reload(val tab: RegistryTab) : RegistryIntent

    data class OpenCommand(val name: String) : RegistryIntent

    /** @param agentId [Agent.id], what the API takes. */
    data class OpenAgent(val agentId: String) : RegistryIntent

    data class OpenModel(val providerId: String, val modelId: String) : RegistryIntent

    data object Close : RegistryIntent
}

sealed interface RegistryEvent {
    data class OpenCommand(val name: String) : RegistryEvent

    data class OpenAgent(val agentId: String) : RegistryEvent

    data class OpenModel(val providerId: String, val modelId: String) : RegistryEvent

    data object Close : RegistryEvent
}

interface RegistryPresenter : Presenter<RegistryState, RegistryIntent, RegistryEvent>

/**
 * One command, `CatalogRoute.CommandDetail` (P10.4 logic, P10.5 screen): its name and description.
 *
 * @property name From the route; never changes.
 * @property command Null until found in [RegistryCatalog.commands].
 * @property error The list could not be read, or [CatalogFailure.NotFound] when it has no command
 *   named [name].
 */
data class CommandDetailState(
    val name: String,
    val loading: Boolean = true,
    val command: Command? = null,
    val error: CatalogFailure? = null,
) {
    override fun toString(): String =
        "CommandDetailState(name=$name, loading=$loading, command=${command?.let { "***" }}, error=$error)"
}

sealed interface CommandDetailIntent {
    data object Retry : CommandDetailIntent

    data object Close : CommandDetailIntent
}

sealed interface CommandDetailEvent {
    data object Close : CommandDetailEvent
}

interface CommandDetailPresenter : Presenter<CommandDetailState, CommandDetailIntent, CommandDetailEvent>

/**
 * One agent, `CatalogRoute.AgentDetail` (P10.4 logic, P10.5 screen): its description, mode, model and
 * permissions. The route's `name` is the agent's ID ([Agent.id]).
 *
 * @property agentId From the route; never changes.
 * @property agent Null until found in [RegistryCatalog.agents], hidden agents included.
 * @property error The list could not be read, or [CatalogFailure.NotFound] when it has no agent with
 *   [agentId].
 */
data class AgentDetailState(
    val agentId: String,
    val loading: Boolean = true,
    val agent: Agent? = null,
    val error: CatalogFailure? = null,
) {
    /** A permission's resource can be a path. */
    override fun toString(): String =
        "AgentDetailState(agentId=$agentId, loading=$loading, agent=${agent?.let { "***" }}, " +
            "permissions=${agent?.permissions?.size}, error=$error)"
}

sealed interface AgentDetailIntent {
    data object Retry : AgentDetailIntent

    /** The agent's model tapped: sends [AgentDetailEvent.OpenModel] when the agent has one. */
    data object OpenModel : AgentDetailIntent

    data object Close : AgentDetailIntent
}

sealed interface AgentDetailEvent {
    data class OpenModel(val providerId: String, val modelId: String) : AgentDetailEvent

    data object Close : AgentDetailEvent
}

interface AgentDetailPresenter : Presenter<AgentDetailState, AgentDetailIntent, AgentDetailEvent>

/**
 * One model, `CatalogRoute.ModelDetail` (P10.4 logic, P10.5 screen): its provider, token limits,
 * prices per tier and variants.
 *
 * @property providerId From the route; never changes.
 * @property modelId From the route; never changes.
 * @property model Null until found in [RegistryCatalog.models].
 * @property providerName The provider's display name; null when the server does not list it.
 * @property defaultModel The server's default, to mark this model when it is the one.
 * @property error The list could not be read, or [CatalogFailure.NotFound] when it has no such model.
 */
data class ModelDetailState(
    val providerId: String,
    val modelId: String,
    val loading: Boolean = true,
    val model: Model? = null,
    val providerName: String? = null,
    val defaultModel: ModelRef? = null,
    val error: CatalogFailure? = null,
) {
    /** The server's default is this model, whatever variant it names. */
    val isDefault: Boolean
        get() = defaultModel != null && defaultModel.providerId == providerId && defaultModel.modelId == modelId
}

sealed interface ModelDetailIntent {
    data object Retry : ModelDetailIntent

    data object Close : ModelDetailIntent
}

sealed interface ModelDetailEvent {
    data object Close : ModelDetailEvent
}

interface ModelDetailPresenter : Presenter<ModelDetailState, ModelDetailIntent, ModelDetailEvent>
