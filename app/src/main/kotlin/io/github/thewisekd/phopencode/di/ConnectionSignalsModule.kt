package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.connection.ForegroundSignalSource
import io.github.thewisekd.phopencode.connection.NetworkSignalSource
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionSignals
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The signals of a fast reconnect (P9.40): the app's [ConnectionManager] as the receiver of
 * [ConnectionSignals], and the two Android sources that feed it, the default network and the
 * foreground. The application starts both once Koin is up.
 */
val connectionSignalsModule: Module = module {
    single<ConnectionSignals> { get<ConnectionManager>() }
    single { NetworkSignalSource(androidContext(), get()) }
    single { ForegroundSignalSource(get()) }
}
