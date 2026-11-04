package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionOptions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app-lifetime scope: a [SupervisorJob], so one failed child does not end the others, on
 * [Dispatchers.Default]. Nothing cancels it; it ends with the process. Request it as the one
 * unqualified [CoroutineScope] of the graph.
 */
val appScopeModule: Module = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
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
 * SSH and the connection to `opencode serve`: the sshj connector, server discovery and startup,
 * the HTTP client over the tunnel, and the one [ConnectionManager] of the app, which runs on the
 * app scope. Creating them opens nothing; the network is used only on [ConnectionManager.connect].
 */
val connectionModule: Module = module {
    single<SshConnector> { SshjConnector() }
    single { ServerResolver(get<SecretStore>()) }
    single<OpencodeClientFactory> { OpencodeClientFactory.Default }
    single { ConnectionOptions() }
    single {
        ConnectionManager(
            connector = get(),
            secrets = get(),
            knownHosts = get(),
            servers = get(),
            clients = get(),
            scope = get(),
            options = get(),
        )
    }
}

/** The core objects the feature modules build on. */
val coreModules: List<Module> = listOf(appScopeModule, storageModule, connectionModule)
