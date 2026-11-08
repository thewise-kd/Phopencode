package io.github.thewisekd.phopencode.feature.catalog.ui

import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ActiveCatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultAgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultCommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultRegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultRegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.status.DefaultStatusPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The catalog feature's definitions, which the app loads at startup. The connection manager comes
 * from the app's core modules.
 *
 * - [CatalogConnection] is a single instance on the connection manager's state: every call reads
 *   the connection standing when it starts.
 * - [SkillCatalog] and [RegistryCatalog] are single instances, so what they keep is shared by the
 *   lists and the detail screens. They take no scope.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   CatalogGraph). The scope is a parameter, never `get()`: the app binds no bare scope.
 */
val catalogModule: Module = module {
    single<CatalogConnection> { ActiveCatalogConnection(state = get<ConnectionManager>().state) }
    single<SkillCatalog> { DefaultSkillCatalog(connection = get()) }
    single<RegistryCatalog> { DefaultRegistryCatalog(connection = get()) }

    factory<SkillsPresenter> { (_: CatalogRoute.Skills, scope: CoroutineScope) ->
        DefaultSkillsPresenter(skills = get(), scope = scope)
    }
    factory<SkillDetailPresenter> { (route: CatalogRoute.SkillDetail, scope: CoroutineScope) ->
        DefaultSkillDetailPresenter(skillId = route.skillId, skills = get(), scope = scope)
    }
    factory<RegistryPresenter> { (_: CatalogRoute.Registry, scope: CoroutineScope) ->
        DefaultRegistryPresenter(registry = get(), scope = scope)
    }
    factory<CommandDetailPresenter> { (route: CatalogRoute.CommandDetail, scope: CoroutineScope) ->
        DefaultCommandDetailPresenter(name = route.name, registry = get(), scope = scope)
    }
    factory<AgentDetailPresenter> { (route: CatalogRoute.AgentDetail, scope: CoroutineScope) ->
        DefaultAgentDetailPresenter(agentId = route.name, registry = get(), scope = scope)
    }
    factory<ModelDetailPresenter> { (route: CatalogRoute.ModelDetail, scope: CoroutineScope) ->
        DefaultModelDetailPresenter(providerId = route.providerId, modelId = route.modelId, registry = get(), scope = scope)
    }
    factory<StatusPresenter> { (_: CatalogRoute.Status, scope: CoroutineScope) ->
        DefaultStatusPresenter(connection = get(), scope = scope)
    }
}
