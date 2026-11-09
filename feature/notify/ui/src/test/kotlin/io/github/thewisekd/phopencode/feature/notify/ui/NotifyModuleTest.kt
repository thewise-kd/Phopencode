package io.github.thewisekd.phopencode.feature.notify.ui

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.notify.logic.contract.ActiveNotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyTopic
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.ui.setup.RemoteNotifyService
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import kotlin.test.assertFailsWith
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

/** Fails every call: no presenter may touch the store before its scope runs. */
private class UntouchedSecrets : SecretStore {
    override suspend fun put(ref: SecretRef, value: SecretValue) = error("Not called")

    override suspend fun read(ref: SecretRef): SecretReadResult = error("Not called")

    override suspend fun delete(ref: SecretRef) = error("Not called")

    override suspend fun deleteAll() = error("Not called")
}

/**
 * `notifyModule` builds each presenter from the parameters the graph passes, the destination's
 * route and scope, and keeps one connection port and one service port for the app. The secret
 * store and the connection manager come from the app's core modules; here they are untouched and
 * idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class NotifyModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin {
        modules(
            notifyModule,
            module {
                single<SecretStore> { UntouchedSecrets() }
                single { idleConnection(appScope) }
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
    fun `every presenter starts from its route`() {
        val koin = koin()

        assertEquals(SetupState(), koin.get<SetupPresenter> { parametersOf(NotifyRoute.Setup, screenScope) }.state.value)
        assertEquals(PendingState(), koin.get<PendingPresenter> { parametersOf(NotifyRoute.Pending, screenScope) }.state.value)
    }

    @Test
    fun `each destination gets its own presenter`() {
        val koin = koin()

        assertNotSame(
            koin.get<SetupPresenter> { parametersOf(NotifyRoute.Setup, screenScope) },
            koin.get<SetupPresenter> { parametersOf(NotifyRoute.Setup, screenScope) },
        )
        assertNotSame(
            koin.get<PendingPresenter> { parametersOf(NotifyRoute.Pending, screenScope) },
            koin.get<PendingPresenter> { parametersOf(NotifyRoute.Pending, screenScope) },
        )
    }

    @Test
    fun `the connection and the service ports are one each, for the app`() {
        val koin = koin()

        assertIs<ActiveNotifyConnection>(koin.get<NotifyConnection>())
        assertSame(koin.get<NotifyConnection>(), koin.get<NotifyConnection>())
        assertIs<RemoteNotifyService>(koin.get<NotifyService>())
        assertSame(koin.get<NotifyService>(), koin.get<NotifyService>())
    }

    @Test
    fun `without a connection the pending list fails as closed and runs nothing`() {
        val koin = koin()
        var ran = false

        val failure = assertFailsWith<ApiException> {
            runBlocking { koin.get<NotifyConnection>().call { _, _ -> ran = true } }
        }

        assertEquals(ApiError.Closed, failure.error)
        assertEquals(false, ran)
    }

    @Test
    fun `without a connection the service fails as not connected`() {
        val service = koin().get<NotifyService>()
        val settings = NtfySettings(NtfyServer.DEFAULT, NtfyTopic.of("a".repeat(NtfyTopic.MIN_LENGTH))!!)

        listOf<suspend () -> Unit>(
            { service.status() },
            { service.install(settings) },
            { service.remove() },
            { service.sendTest(settings) },
        ).forEach { call ->
            val failure = assertFailsWith<NotifyException> { runBlocking { call() } }
            assertEquals(NotifyFailure.NotConnected, failure.failure)
        }
    }
}
