package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The port every catalog presenter and cache reads the server through: the catalog of the active
 * connection. Catalog routes carry no folder, so every list is the server's default
 * (`location = null`).
 *
 * Shared rules for the catalog screens:
 * - View only: nothing here changes the server's configuration.
 * - Implementations never log a skill's text, a path, a description or a server error.
 */
interface CatalogConnection {
    /**
     * The generation of the connection standing now (`ConnectionState.Connected.generation`), null
     * while there is none; emits the current value on collection, then each change once. A cache
     * keys what it keeps by it, so a list read from one connection, or one server, is never shown
     * for another.
     */
    val generations: Flow<Long?>

    /**
     * Runs [block] with the catalog of the connection standing right now and that connection's
     * generation.
     *
     * @throws ApiException With [ApiError.Closed] when no connection stands, without running
     *   [block]; or whatever [block] throws, which for [CatalogApi] is an [ApiException] too.
     */
    suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T
}

/**
 * [CatalogConnection] on the app's connection state, `ConnectionManager.state`: a call takes the
 * client of the `Connected` state current when it starts. If that connection ends meanwhile, the
 * client's calls fail with [ApiError.Closed]; they never move to a later connection.
 */
class ActiveCatalogConnection(private val state: StateFlow<ConnectionState>) : CatalogConnection {
    override val generations: Flow<Long?> =
        state.map { (it as? ConnectionState.Connected)?.generation }.distinctUntilChanged()

    override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T {
        val current = state.value as? ConnectionState.Connected ?: throw ApiException(ApiError.Closed)
        return block(current.client.catalog, current.generation)
    }

    override fun toString(): String = "ActiveCatalogConnection"
}

/**
 * The server's skills, read once per connection and kept (P10.2), shared by the skill list and the
 * skill details so that a skill's full text is fetched once. `CatalogModule` makes one for the app;
 * `skills/DefaultSkillCatalog` is the implementation.
 */
interface SkillCatalog {
    /**
     * The skills of the connection standing now: those kept for it, or read and kept when there are
     * none yet, when the connection changed since, or when [refresh] asks for a fresh read.
     *
     * @throws ApiException As [CatalogConnection.call].
     */
    suspend fun skills(refresh: Boolean = false): List<Skill>
}

/**
 * The server's commands, agents and models, each read once per connection and kept (P10.4), shared
 * by the registry's tabs and the detail screens. `CatalogModule` makes one for the app;
 * `registry/DefaultRegistryCatalog` is the implementation. Each list is kept apart, so one that
 * fails leaves the others.
 *
 * Every function reads as [SkillCatalog.skills] does and throws as it does.
 */
interface RegistryCatalog {
    suspend fun commands(refresh: Boolean = false): List<Command>

    /** All agents the server lists, hidden ones included; the presenters filter. */
    suspend fun agents(refresh: Boolean = false): List<Agent>

    suspend fun models(refresh: Boolean = false): ModelCatalog
}

/**
 * The models with what the screens show beside them.
 *
 * @property providers For their display names; a model whose provider is not listed shows its ID.
 * @property defaultModel The server's default; null when it has none.
 */
data class ModelCatalog(
    val models: List<Model>,
    val providers: List<Provider> = emptyList(),
    val defaultModel: ModelRef? = null,
) {
    fun providerName(providerId: String): String? = providers.firstOrNull { it.id == providerId }?.name

    override fun toString(): String =
        "ModelCatalog(models=${models.size}, providers=${providers.size}, defaultModel=$defaultModel)"
}
