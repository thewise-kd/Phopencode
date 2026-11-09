package io.github.thewisekd.phopencode.feature.notify.ui

import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.notify.logic.contract.ActiveNotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.pending.DefaultPendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.setup.DefaultSetupPresenter
import io.github.thewisekd.phopencode.feature.notify.ui.setup.RemoteNotifyService
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The notify feature's definitions, which the app loads at startup. The secret store and the
 * connection manager come from the app's core modules.
 *
 * - [NotifyConnection] is a single instance on the connection manager's state: every call reads
 *   the connection standing when it starts.
 * - [NotifyService] is the app's one [RemoteNotifyService], the adapter to the remote installation
 *   in `core/remote`. Neither takes a scope.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   NotifyGraph). The scope is a parameter, never `get()`: the app binds no bare scope. The setup
 *   gets a `SecureRandom` of its own for making a topic.
 */
val notifyModule: Module = module {
    single<NotifyConnection> { ActiveNotifyConnection(state = get<ConnectionManager>().state) }
    single<NotifyService> { RemoteNotifyService(connection = get()) }

    factory<SetupPresenter> { (_: NotifyRoute.Setup, scope: CoroutineScope) ->
        DefaultSetupPresenter(secrets = get(), service = get(), random = SecureRandom(), scope = scope)
    }
    factory<PendingPresenter> { (_: NotifyRoute.Pending, scope: CoroutineScope) ->
        DefaultPendingPresenter(connection = get(), scope = scope)
    }
}
