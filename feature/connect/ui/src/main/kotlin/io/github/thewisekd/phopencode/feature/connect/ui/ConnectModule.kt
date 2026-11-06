package io.github.thewisekd.phopencode.feature.connect.ui

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.folder.DefaultFolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.hostkey.DefaultHostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.keys.DefaultKeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.progress.DefaultProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.serverform.DefaultServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.serverlist.DefaultServerListPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The connect feature's definitions, which the app loads at startup. The storage, SSH and
 * connection objects come from the app's core modules.
 *
 * Each presenter is a factory under its contract interface, built per screen with two parameters:
 * the screen's route and the screen's coroutine scope (see `rememberPresenter` in ConnectGraph).
 * The scope is a parameter, never `get()`: the graph binds no bare scope, and the app's singletons
 * take the app's through a type of their own that no presenter is given as a parameter.
 */
val connectModule: Module = module {
    single { ActiveServer() }

    factory<ServerListPresenter> { (_: ConnectRoute.ServerList, scope: CoroutineScope) ->
        DefaultServerListPresenter(
            profiles = get(),
            secrets = get(),
            keyIndex = get(),
            recentFolders = get(),
            scope = scope,
        )
    }
    factory<ServerFormPresenter> { (route: ConnectRoute.ServerForm, scope: CoroutineScope) ->
        DefaultServerFormPresenter(
            serverId = route.serverId?.let(::ServerProfileId),
            profiles = get(),
            secrets = get(),
            keyIndex = get(),
            scope = scope,
        )
    }
    factory<KeysPresenter> { (_: ConnectRoute.Keys, scope: CoroutineScope) ->
        DefaultKeysPresenter(
            keyIndex = get(),
            secrets = get(),
            profiles = get(),
            scope = scope,
        )
    }
    factory<HostKeyPresenter> { (route: ConnectRoute.HostKey, scope: CoroutineScope) ->
        DefaultHostKeyPresenter(
            serverId = ServerProfileId(route.serverId),
            connection = get(),
            knownHosts = get(),
            profiles = get(),
            scope = scope,
        )
    }
    factory<FolderPresenter> { (_: ConnectRoute.FolderPicker, scope: CoroutineScope) ->
        DefaultFolderPresenter(
            connection = get(),
            activeServer = get(),
            recentFolders = get(),
            scope = scope,
        )
    }
    factory<ProgressPresenter> { (route: ConnectRoute.Progress, scope: CoroutineScope) ->
        DefaultProgressPresenter(
            serverId = ServerProfileId(route.serverId),
            connection = get(),
            profiles = get(),
            secrets = get(),
            activeServer = get(),
            scope = scope,
        )
    }
}
