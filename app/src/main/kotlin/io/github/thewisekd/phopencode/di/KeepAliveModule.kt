package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.keepalive.FileNotificationAsks
import io.github.thewisekd.phopencode.keepalive.KeepAliveConnection
import io.github.thewisekd.phopencode.keepalive.KeepAliveStarter
import io.github.thewisekd.phopencode.keepalive.KeepAliveWatcher
import io.github.thewisekd.phopencode.keepalive.ManagerKeepAliveConnection
import io.github.thewisekd.phopencode.keepalive.NotificationAsks
import io.github.thewisekd.phopencode.keepalive.ServiceKeepAliveStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The keep-alive (P9.39): the app's [ConnectionManager] as the keep-alive sees it, the starter of
 * its service, and the one watcher, which follows the connection on the app scope, on the main
 * thread. The application starts the watcher once Koin is up. The record of asking for the
 * notification permission (P9.41) is for MainActivity, which asks once per install.
 */
val keepAliveModule: Module = module {
    single<KeepAliveConnection> { ManagerKeepAliveConnection(get()) }
    single<KeepAliveStarter> { ServiceKeepAliveStarter(androidContext()) }
    // On the main thread, where services are usually started; the app scope's job ends it with the process.
    single { KeepAliveWatcher(get(), get<AppScope>().scope + Dispatchers.Main, get()) }
    single<NotificationAsks> { FileNotificationAsks(androidContext()) }
}
