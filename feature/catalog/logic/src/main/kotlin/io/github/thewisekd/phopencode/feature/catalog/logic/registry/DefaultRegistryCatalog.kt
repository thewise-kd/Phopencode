package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog

/**
 * Stub from the contract (P10.1): reads every list on every call and keeps nothing. P10.4 fills it in
 * without changing the constructor: each list kept per connection generation, as [RegistryCatalog]
 * describes. `CatalogModule` makes one for the app; it takes no scope, so it never captures a
 * screen's.
 *
 * @param connection The catalog of the active connection.
 */
class DefaultRegistryCatalog(private val connection: CatalogConnection) : RegistryCatalog {
    override suspend fun commands(refresh: Boolean): List<Command> = connection.call { catalog, _ -> catalog.commands() }

    override suspend fun agents(refresh: Boolean): List<Agent> = connection.call { catalog, _ -> catalog.agents() }

    override suspend fun models(refresh: Boolean): ModelCatalog = connection.call { catalog, _ ->
        ModelCatalog(models = catalog.models(), providers = catalog.providers(), defaultModel = catalog.defaultModel())
    }

    /** Keeps descriptions and permissions out of logs. */
    override fun toString(): String = "DefaultRegistryCatalog"
}
