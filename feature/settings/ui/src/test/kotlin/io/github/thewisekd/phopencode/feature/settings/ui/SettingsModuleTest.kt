package io.github.thewisekd.phopencode.feature.settings.ui

import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Disconnector
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.AppCompatLocaleApplier
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** An implementation that fails every call; the idle manager below never makes one. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Not used: ${method.name}")
    } as T

/** A manager that is never told to connect, as the app's is before the user picks a server. */
private fun idleConnection(scope: CoroutineScope) = ConnectionManager(
    connector = unused<SshConnector>(),
    secrets = unused<SecretStore>(),
    knownHosts = unused<KnownHostsStore>(),
    servers = ServerResolver(unused<SecretStore>(), unused<ServerTools>()),
    clients = OpencodeClientFactory { _, _, _ -> error("Not used") },
    scope = scope,
)

/** Stored in memory; the module only passes it on. */
private class MemorySettings : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun observe(): Flow<AppSettings> = settings

    override suspend fun setLanguage(language: AppLanguage) {
        settings.value = settings.value.copy(language = language)
    }

    override suspend fun setTheme(theme: ThemePreference) {
        settings.value = settings.value.copy(theme = theme)
    }

    override suspend fun setFileSizeLimit(bytes: Long) {
        settings.value = settings.value.copy(fileSizeLimitBytes = bytes)
    }

    override suspend fun deleteAllData() {
        settings.value = AppSettings()
    }
}

/**
 * `settingsModule` builds the presenter from the parameters the graph passes, the destination's
 * route and scope, and binds the locale and the disconnect ports. The settings store and the
 * connection manager come from the app's core modules; here they are in memory and idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SettingsModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())
    private val connection = idleConnection(appScope)

    private fun koin(): Koin = startKoin {
        modules(
            settingsModule,
            module {
                single<SettingsRepository> { MemorySettings() }
                single { connection }
            },
        )
    }.koin

    @After
    fun stop() {
        stopKoin()
        appScope.cancel()
        screenScope.cancel()
    }

    @Test
    fun `the preferences start loading, one presenter per destination`() {
        val koin = koin()
        val first = koin.get<PreferencesPresenter> { parametersOf(SettingsRoute.Preferences, screenScope) }

        assertEquals(PreferencesState(), first.state.value)
        assertNotSame(first, koin.get<PreferencesPresenter> { parametersOf(SettingsRoute.Preferences, screenScope) })
    }

    @Test
    fun `the language is applied through AppCompat, one for the app`() {
        val koin = koin()

        assertIs<AppCompatLocaleApplier>(koin.get<LocaleApplier>())
        assertSame(koin.get<LocaleApplier>(), koin.get<LocaleApplier>())
    }

    @Test
    fun `disconnecting ends the app's connection`() {
        val koin = koin()

        runBlocking { koin.get<Disconnector>().disconnect() }

        assertEquals(ConnectionState.Idle, connection.state.value)
    }
}
