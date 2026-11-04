package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId

/**
 * A new session on the active connection's server (P5.4, P5.5).
 *
 * Where it comes from: the agents, models and default model from `CatalogApi` for the folder
 * (`Location(directory)`), loaded again when the folder changes; the recent folders from
 * `RecentFoldersStore` for the profile in `ConnectionState.Connected.server`. Creating sends
 * `SessionApi.create` with the folder as `CreateSessionRequest.location`, and the title, agent and
 * model when chosen; a choice left null leaves it to the server. On success the folder is added to
 * the recent folders and [SessionCreateEvent.OpenChat] follows.
 *
 * @property loading The catalog is loading.
 * @property directory The folder the session works in, as typed or picked: an absolute path on the
 *   server.
 * @property recentFolders The active server's recent folders, newest first, to pick from.
 * @property agents The agents to pick from: [selectableAgents] of the catalog.
 * @property selectedAgent An [Agent.id]; null leaves it to the server.
 * @property models The models to pick from.
 * @property selectedModel Null leaves it to the agent's or the server's default.
 * @property defaultModel The server's default for the folder, shown when nothing is picked.
 * @property title Null-equivalent when blank: the server names the session from its first prompt.
 *   Prints masked.
 * @property skillId The skill from the route, to attach to the first prompt.
 * @property catalogError The catalog could not be loaded. Creating still works with the server's
 *   defaults.
 * @property error Creating failed.
 */
data class SessionCreateState(
    val loading: Boolean = true,
    val directory: String = "",
    val recentFolders: List<String> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val selectedAgent: String? = null,
    val models: List<Model> = emptyList(),
    val selectedModel: ModelRef? = null,
    val defaultModel: ModelRef? = null,
    val title: String = "",
    val skillId: String? = null,
    val folderError: CreateFolderError? = null,
    val catalogError: SessionsFailure? = null,
    val creating: Boolean = false,
    val error: SessionsFailure? = null,
) {
    /** Whether [SessionCreateIntent.Create] can be sent; the presenter still checks the folder. */
    val canCreate: Boolean
        get() = !loading && !creating && directory.isNotBlank()

    override fun toString(): String =
        "SessionCreateState(loading=$loading, directory=$directory, recentFolders=$recentFolders, " +
            "agents=${agents.map { it.id }}, selectedAgent=$selectedAgent, models=${models.size}, " +
            "selectedModel=$selectedModel, defaultModel=$defaultModel, title=***, skillId=$skillId, " +
            "folderError=$folderError, catalogError=$catalogError, creating=$creating, error=$error)"
}

/** Why the folder cannot be used. */
enum class CreateFolderError {
    Missing,

    /** Not an absolute path: it must start with `/`. */
    NotAbsolute,
}

/**
 * The agents a new session can start with: primary ones (mode `Primary` or `All`), without the
 * internal ones the server marks hidden, in the catalog's order.
 */
fun selectableAgents(agents: List<Agent>): List<Agent> =
    agents.filter { !it.hidden && (it.mode == AgentMode.Primary || it.mode == AgentMode.All) }

sealed interface SessionCreateIntent {
    /** The folder as typed, or a recent folder picked. */
    data class FolderChanged(val directory: String) : SessionCreateIntent

    /** @property agentId Null goes back to the server's choice. */
    data class AgentSelected(val agentId: String?) : SessionCreateIntent

    /** @property model Null goes back to the default. */
    data class ModelSelected(val model: ModelRef?) : SessionCreateIntent

    data class TitleChanged(val title: String) : SessionCreateIntent {
        override fun toString(): String = "TitleChanged(***)"
    }

    data object Create : SessionCreateIntent

    /** Loads the catalog again after [SessionCreateState.catalogError]. */
    data object Retry : SessionCreateIntent

    data object DismissError : SessionCreateIntent

    data object Cancel : SessionCreateIntent
}

sealed interface SessionCreateEvent {
    /** The session was created; the chat replaces the creation screen. */
    data class OpenChat(val id: SessionId) : SessionCreateEvent

    data object Close : SessionCreateEvent
}

interface SessionCreatePresenter : Presenter<SessionCreateState, SessionCreateIntent, SessionCreateEvent>
