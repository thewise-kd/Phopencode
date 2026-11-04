package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionOptions
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.storage.repo.StorageRepositories
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The storage and connection objects, resolved from the Koin graph that `PhopencodeApplication`
 * starts. The levels of `MainActivityTest`: on the Pi only level 26 runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CoreModulesTest {
    @get:Rule
    val stopKoin = StopKoinRule()

    private val koin: Koin get() = GlobalContext.get()

    private fun assertSingle(type: KClass<*>) {
        val first = koin.get<Any>(type)
        assertSame(first, koin.get<Any>(type), "${type.simpleName} is not a single instance")
    }

    @Test
    fun `every storage and connection binding is one instance per process`() {
        listOf(
            SecretStore::class,
            StorageRepositories::class,
            ProfileRepository::class,
            KnownHostsStore::class,
            SettingsRepository::class,
            SshKeyIndex::class,
            RecentFoldersStore::class,
            SshConnector::class,
            ServerResolver::class,
            OpencodeClientFactory::class,
            ConnectionOptions::class,
            ConnectionManager::class,
            CoroutineScope::class,
        ).forEach(::assertSingle)
    }

    @Test
    fun `the storage interfaces are the real stores of the one repository set`() {
        val repositories = koin.get<StorageRepositories>()

        assertSame(repositories.profiles, koin.get<ProfileRepository>())
        assertSame(repositories.knownHosts, koin.get<KnownHostsStore>())
        assertSame(repositories.settings, koin.get<SettingsRepository>())
        assertSame(repositories.keyIndex, koin.get<SshKeyIndex>())
        assertSame(repositories.recentFolders, koin.get<RecentFoldersStore>())
        // The implementations are internal to core/storage; their names tell the real ones.
        assertEquals("TinkSecretStore", koin.get<SecretStore>()::class.simpleName)
        listOf(
            koin.get<ProfileRepository>(),
            koin.get<KnownHostsStore>(),
            koin.get<SettingsRepository>(),
            koin.get<SshKeyIndex>(),
            koin.get<RecentFoldersStore>(),
        ).forEach { store ->
            val name = assertNotNull(store::class.simpleName)
            assertTrue(name.startsWith("DataStore"), "$name is not a DataStore implementation")
        }
    }

    @Test
    fun `the stores read their files`() = runBlocking {
        assertEquals(emptyList(), koin.get<ProfileRepository>().list())
    }

    @Test
    fun `the connector is the sshj one and the client factory the HTTP default`() {
        assertIs<SshjConnector>(koin.get<SshConnector>())
        assertSame(OpencodeClientFactory.Default, koin.get<OpencodeClientFactory>())
        assertEquals(ConnectionOptions(), koin.get<ConnectionOptions>())
    }

    @Test
    fun `the connection manager resolves idle, without a network`() {
        val manager = koin.get<ConnectionManager>()

        assertEquals(ConnectionState.Idle, manager.state.value)
    }

    @Test
    fun `the app scope is alive and survives a failed child`() {
        val scope = koin.get<CoroutineScope>()
        val job = assertNotNull(scope.coroutineContext[Job])

        assertTrue(job.isActive)
        // A SupervisorJob: one failing child does not cancel the scope and every other child.
        val failing = Job(job).apply { completeExceptionally(IllegalStateException("test")) }
        assertTrue(failing.isCancelled)
        assertTrue(job.isActive)
    }
}
