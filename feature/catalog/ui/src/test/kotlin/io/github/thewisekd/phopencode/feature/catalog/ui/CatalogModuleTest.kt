package io.github.thewisekd.phopencode.feature.catalog.ui

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ActiveCatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.catalog.logic.registry.DefaultRegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.skills.DefaultSkillCatalog
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

private const val SKILL = "gözden geçir"
private const val PROVIDER = "provider"
private const val MODEL = "model/large"

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

/**
 * `catalogModule` builds every presenter from the parameters the graph passes, the destination's
 * route and scope, and keeps one connection port and one cache of each kind for the app. The
 * manager comes from the app's core modules; here it is an idle one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class CatalogModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin {
        modules(
            catalogModule,
            module { single { idleConnection(appScope) } },
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

        assertEquals(SkillsState(), koin.get<SkillsPresenter> { parametersOf(CatalogRoute.Skills, screenScope) }.state.value)
        assertEquals(
            SkillDetailState(skillId = SKILL),
            koin.get<SkillDetailPresenter> { parametersOf(CatalogRoute.SkillDetail(SKILL), screenScope) }.state.value,
        )
        assertEquals(
            RegistryState(),
            koin.get<RegistryPresenter> { parametersOf(CatalogRoute.Registry, screenScope) }.state.value,
        )
        assertEquals(
            CommandDetailState(name = "review"),
            koin.get<CommandDetailPresenter> { parametersOf(CatalogRoute.CommandDetail("review"), screenScope) }.state.value,
        )
        assertEquals(
            AgentDetailState(agentId = "build"),
            koin.get<AgentDetailPresenter> { parametersOf(CatalogRoute.AgentDetail("build"), screenScope) }.state.value,
        )
        assertEquals(
            ModelDetailState(providerId = PROVIDER, modelId = MODEL),
            koin.get<ModelDetailPresenter> { parametersOf(CatalogRoute.ModelDetail(PROVIDER, MODEL), screenScope) }.state.value,
        )
        assertEquals(StatusState(), koin.get<StatusPresenter> { parametersOf(CatalogRoute.Status, screenScope) }.state.value)
    }

    @Test
    fun `each destination gets its own presenter`() {
        val koin = koin()

        assertNotSame(
            koin.get<SkillsPresenter> { parametersOf(CatalogRoute.Skills, screenScope) },
            koin.get<SkillsPresenter> { parametersOf(CatalogRoute.Skills, screenScope) },
        )
    }

    @Test
    fun `the connection and the caches are one each, for the app`() {
        val koin = koin()

        assertIs<ActiveCatalogConnection>(koin.get<CatalogConnection>())
        assertSame(koin.get<CatalogConnection>(), koin.get<CatalogConnection>())
        assertIs<DefaultSkillCatalog>(koin.get<SkillCatalog>())
        assertSame(koin.get<SkillCatalog>(), koin.get<SkillCatalog>())
        assertIs<DefaultRegistryCatalog>(koin.get<RegistryCatalog>())
        assertSame(koin.get<RegistryCatalog>(), koin.get<RegistryCatalog>())
    }

    @Test
    fun `without a connection the catalog fails as closed and runs nothing`() {
        val koin = koin()
        var ran = false

        val failure = assertFailsWith<ApiException> {
            runBlocking { koin.get<CatalogConnection>().call { _, _ -> ran = true } }
        }

        assertEquals(ApiError.Closed, failure.error)
        assertEquals(false, ran)
    }
}
