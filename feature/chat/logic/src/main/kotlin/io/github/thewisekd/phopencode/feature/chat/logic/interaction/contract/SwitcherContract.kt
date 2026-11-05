package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * The session's agent, model and model variant, and switching them from a bottom sheet (P7.10
 * builds it, P7.11 draws it).
 *
 * Where it comes from: [agent] and [model] are `Session.agent` and `Session.model` of
 * `ChatSessionState.session`, which the sync keeps current. The lists are `CatalogApi.agents` and
 * `CatalogApi.models` for the session's folder (`Location(session.directory)`), asked through
 * `ChatSession.call` when the sheet first opens, and again on [SwitcherIntent.Retry]. A hidden
 * agent (`Agent.hidden`) is never listed, nor one of mode `AgentMode.Subagent`, which cannot lead
 * a session.
 *
 * Switching: `MessageApi.switchAgent(sessionId, id)` and `MessageApi.switchModel(sessionId, ref)`,
 * the variant as `ModelRef.variant`, through `ChatSession.call`. One switch at a time; the sheet
 * closes once the server accepts one, and stays open with [error] when it fails. What is current
 * follows the session, so it changes when the server says so, not before.
 *
 * @property enabled The session is loaded and its server connected.
 * @property open The sheet is shown.
 * @property loading The lists are being asked for.
 * @property agent The current agent's `Agent.id`.
 * @property model The current model, with its variant.
 * @property agents The agents to pick from, in the server's order.
 * @property models The models to pick from, in the server's order.
 * @property switching A switch is on its way.
 * @property error The last list or switch failed.
 */
data class SwitcherState(
    val enabled: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val agent: String? = null,
    val model: ModelRef? = null,
    val agents: List<AgentOption> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val switching: Boolean = false,
    val error: SwitcherError? = null,
) {
    /** Whether a pick would be sent. */
    val canSwitch: Boolean
        get() = enabled && !switching
}

/** @property id `Agent.id`, what the API takes; [name] is its display form. */
data class AgentOption(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** A model, with the variants it offers. */
data class ModelOption(
    val providerId: String,
    val modelId: String,
    val name: String,
    val variants: List<String> = emptyList(),
) {
    fun ref(variant: String? = null): ModelRef = ModelRef(providerId, modelId, variant)

    /** Whether [current] is this model, in any variant. */
    fun isCurrent(current: ModelRef?): Boolean =
        current != null && current.providerId == providerId && current.modelId == modelId
}

enum class SwitcherAction {
    Load,
    SwitchAgent,
    SwitchModel,
}

data class SwitcherError(val action: SwitcherAction, val reason: ChatFailure)

sealed interface SwitcherIntent {
    data object Open : SwitcherIntent

    data object Close : SwitcherIntent

    /** Switches to the agent [id]; the current one does nothing. */
    data class PickAgent(val id: String) : SwitcherIntent

    /** Switches to [model], its variant included; the current one does nothing. */
    data class PickModel(val model: ModelRef) : SwitcherIntent

    /** Asks for the lists again after a failed load. */
    data object Retry : SwitcherIntent

    data object DismissError : SwitcherIntent
}

/** The switcher has no one-time events. */
interface SwitcherPresenter : Presenter<SwitcherState, SwitcherIntent, Nothing>
