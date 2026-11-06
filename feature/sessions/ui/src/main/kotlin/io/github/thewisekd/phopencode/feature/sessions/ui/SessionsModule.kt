package io.github.thewisekd.phopencode.feature.sessions.ui

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.create.DefaultSessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.list.DefaultSessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.manage.DefaultSessionManagePresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The sessions feature's definitions, which the app loads at startup. The connection manager and the
 * recent folders store come from the app's core modules.
 *
 * Each presenter is a factory under its contract interface, built per screen with two parameters:
 * the screen's route and the screen's coroutine scope (see `rememberPresenter` in SessionsGraph).
 * The scope is a parameter, never `get()`: the graph binds no bare scope, and the app's singletons
 * take the app's through a type of their own that no presenter is given as a parameter.
 */
val sessionsModule: Module = module {
    factory<SessionListPresenter> { (route: SessionsRoute.SessionList, scope: CoroutineScope) ->
        DefaultSessionListPresenter(
            directory = route.directory,
            connection = get(),
            scope = scope,
        )
    }
    factory<SessionCreatePresenter> { (route: SessionsRoute.Create, scope: CoroutineScope) ->
        DefaultSessionCreatePresenter(
            directory = route.directory,
            skillId = route.skillId,
            connection = get(),
            recentFolders = get(),
            scope = scope,
        )
    }
    factory<SessionManagePresenter> { (route: SessionsRoute.Manage, scope: CoroutineScope) ->
        DefaultSessionManagePresenter(
            sessionId = SessionId(route.sessionId),
            connection = get(),
            scope = scope,
        )
    }
}
