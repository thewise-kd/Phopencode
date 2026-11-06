package io.github.thewisekd.phopencode.di

import android.content.pm.ApplicationInfo
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionOptions
import io.github.thewisekd.phopencode.core.remote.connection.Diagnostics
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.storage.repo.StorageRepositories
import io.github.thewisekd.phopencode.core.storage.secret.androidSecretStore
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.files.ui.TransferQueueScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app-lifetime scope: a [SupervisorJob], so one failed child does not end the others, on
 * [Dispatchers.Default]. Nothing cancels it; it ends with the process.
 *
 * It is bound only as [AppScope], never as a bare [CoroutineScope]: presenters take their screen's
 * scope as a Koin parameter, and Koin answers a `get()` from the parameters of the resolution
 * first, so a single created while a presenter is resolved would capture the screen's scope. A
 * single that needs the app scope asks for `get<AppScope>().scope`. A feature that cannot see
 * [AppScope] declares a wrapper of its own, which this module binds to the same scope:
 * [TransferQueueScope] for the files feature's transfer queue.
 */
val appScopeModule: Module = module {
    single { AppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single { TransferQueueScope(get<AppScope>().scope) }
}

/**
 * The secret store and the DataStore repositories, bound to their `core/storage-api` interfaces.
 * Every one is a single instance: DataStore refuses a second instance on the same file, and the
 * secret store does not coordinate with another one on its directory.
 */
val storageModule: Module = module {
    single<SecretStore> { androidSecretStore(androidContext()) }
    single { StorageRepositories(androidContext(), get<SecretStore>()) }
    single<ProfileRepository> { get<StorageRepositories>().profiles }
    single<KnownHostsStore> { get<StorageRepositories>().knownHosts }
    single<SettingsRepository> { get<StorageRepositories>().settings }
    single<SshKeyIndex> { get<StorageRepositories>().keyIndex }
    single<RecentFoldersStore> { get<StorageRepositories>().recentFolders }
}

/**
 * [Diagnostics] on exactly when the installed app is debuggable, read at runtime from its
 * [ApplicationInfo.flags], so no build file decides it: a release build is not debuggable and
 * keeps them off.
 */
internal fun diagnosticsOf(info: ApplicationInfo): Diagnostics =
    Diagnostics(enabled = (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

/**
 * SSH and the connection to `opencode serve`: the sshj connector, server discovery and startup,
 * the HTTP client over the tunnel, and the one [ConnectionManager] of the app, which runs on the
 * app scope. Creating them opens nothing; the network is used only on [ConnectionManager.connect].
 * The manager's [Diagnostics] are on only in a debuggable build (see [diagnosticsOf]); it then
 * logs each end of a connection through `java.util.logging`, which reaches Logcat with the tag
 * `phopencode`.
 */
val connectionModule: Module = module {
    single<SshConnector> { SshjConnector() }
    single { ServerResolver(get<SecretStore>()) }
    single<OpencodeClientFactory> { OpencodeClientFactory.Default }
    single { ConnectionOptions() }
    single { diagnosticsOf(androidContext().applicationInfo) }
    single {
        ConnectionManager(
            connector = get(),
            secrets = get(),
            knownHosts = get(),
            servers = get(),
            clients = get(),
            scope = get<AppScope>().scope,
            options = get(),
            diagnostics = get(),
        )
    }
}

/** The core objects the feature modules build on. */
val coreModules: List<Module> = listOf(appScopeModule, storageModule, connectionModule)
