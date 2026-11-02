package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Skill

/**
 * What the server offers, view only. Errors are described on [OpencodeClient].
 *
 * A project can add entries of its own, so each list takes the folder it is for; null is the
 * server's default.
 */
interface CatalogApi {
    /** Carries each skill's full text, so callers fetch it once and cache it. */
    suspend fun skills(location: Location? = null): List<Skill>

    suspend fun commands(location: Location? = null): List<Command>

    suspend fun agents(location: Location? = null): List<Agent>

    suspend fun models(location: Location? = null): List<Model>

    /** Null when the server has no default. */
    suspend fun defaultModel(location: Location? = null): ModelRef?

    suspend fun providers(location: Location? = null): List<Provider>

    suspend fun mcpServers(location: Location? = null): List<McpServer>

    suspend fun plugins(location: Location? = null): List<Plugin>
}

/**
 * A model provider. The server sends `settings` and `headers` in clear, API key included, so a decoder
 * must drop them and this type has no place for them.
 */
data class Provider(
    val id: String,
    val name: String,
)

/**
 * @property status From the nested `status.status`.
 * @property error Why the connection failed, in the server's words, from `status.error`. Left out of
 *   [toString].
 */
data class McpServer(
    val name: String,
    val status: McpStatus,
    val error: String? = null,
) {
    /** An MCP error can quote the server's URL, which may hold a token. */
    override fun toString(): String = "McpServer(name=$name, status=$status)"
}

sealed interface McpStatus {
    data object Connected : McpStatus

    data object Pending : McpStatus

    data object Disabled : McpStatus

    data object Failed : McpStatus

    data object NeedsAuth : McpStatus

    data class Unknown(val raw: String) : McpStatus
}

/**
 * @property id Optional in the server's spec.
 * @property status From `state.status`.
 * @property error Why the plugin failed, from `state.error`. Left out of [toString].
 */
data class Plugin(
    val id: String?,
    val status: PluginStatus,
    val error: String? = null,
) {
    /** A plugin error can quote a path or a package address. */
    override fun toString(): String = "Plugin(id=$id, status=$status)"
}

sealed interface PluginStatus {
    data object Active : PluginStatus

    data object Failed : PluginStatus

    data class Unknown(val raw: String) : PluginStatus
}
