package io.github.thewisekd.phopencode.feature.connect.logic.serverlist

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultServerListPresenterTest {
    private val profiles = FakeProfileRepository()
    private val secrets = FakeSecretStore()
    private val keyIndex = FakeSshKeyIndex()
    private val recentFolders = FakeRecentFoldersStore()

    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private fun profile(
        id: String,
        name: String = id,
        lastUsedAt: Instant? = null,
        auth: SshAuth = SshAuth.Password(SecretRef("$id-password")),
        opencodeCredential: SecretRef? = null,
    ) = ServerProfile(
        id = ServerProfileId(id),
        name = name,
        host = "server.example",
        username = "user",
        auth = auth,
        opencodeCredential = opencodeCredential,
        lastUsedAt = lastUsedAt,
    )

    private fun TestScope.presenter(repository: ProfileRepository = profiles) = DefaultServerListPresenter(
        profiles = repository,
        secrets = secrets,
        keyIndex = keyIndex,
        recentFolders = recentFolders,
        scope = backgroundScope,
        clock = clock,
    )

    private suspend fun putSecret(ref: SecretRef) {
        SecretValue.ofBytes(byteArrayOf(1, 2, 3)).use { secrets.put(ref, it) }
    }

    private suspend fun exists(ref: SecretRef) = secrets.read(ref) !is SecretReadResult.NotFound

    private fun TestScope.collectEvents(presenter: DefaultServerListPresenter): List<ServerListEvent> {
        val events = mutableListOf<ServerListEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        return events
    }

    @Test
    fun `the initial state holds until the scope runs`() = runTest {
        profiles.save(profile("a"))

        val presenter = presenter()

        assertEquals(ServerListState(), presenter.state.value)
        runCurrent()
        assertEquals(listOf(profile("a")), presenter.state.value.servers)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `loading does not run in the constructor even on an immediate dispatcher`() = runTest {
        profiles.save(profile("a"))
        // Like Dispatchers.Main.immediate on the main thread: a launch runs at once, a yield dispatches.
        val queue = StandardTestDispatcher(testScheduler)
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + immediate)

        val presenter = DefaultServerListPresenter(profiles, secrets, keyIndex, recentFolders, scope, clock)

        assertEquals(ServerListState(), presenter.state.value)
        runCurrent()
        assertEquals(listOf(profile("a")), presenter.state.value.servers)
    }

    @Test
    fun `an empty list loads as empty, not loading`() = runTest {
        val presenter = presenter()
        runCurrent()

        assertEquals(ServerListState(loading = false), presenter.state.value)
    }

    @Test
    fun `servers are listed most recently used first, never used last`() = runTest {
        profiles.save(profile("never", name = "A never"))
        profiles.save(profile("old", lastUsedAt = Instant.parse("2026-01-01T00:00:00Z")))
        profiles.save(profile("new", lastUsedAt = Instant.parse("2026-06-01T00:00:00Z")))

        val presenter = presenter()
        runCurrent()

        assertEquals(listOf("new", "old", "never"), presenter.state.value.servers.map { it.id.value })
    }

    @Test
    fun `the list follows changes in the repository`() = runTest {
        val presenter = presenter()
        runCurrent()

        profiles.save(profile("a"))
        runCurrent()

        assertEquals(listOf("a"), presenter.state.value.servers.map { it.id.value })
    }

    @Test
    fun `a load failure shows LoadFailed and does not crash`() = runTest {
        val failing = object : ProfileRepository by profiles {
            override fun observe(): Flow<List<ServerProfile>> = flow { throw IOException("unreachable") }
        }

        val presenter = presenter(failing)
        runCurrent()

        assertEquals(ServerListState(loading = false, error = ServerListError.LoadFailed), presenter.state.value)
    }

    @Test
    fun `an observe call that throws instead of returning a flow shows LoadFailed`() = runTest {
        val failing = object : ProfileRepository by profiles {
            override fun observe(): Flow<List<ServerProfile>> = throw IOException("unreachable")
        }

        val presenter = presenter(failing)
        runCurrent()

        assertEquals(ServerListState(loading = false, error = ServerListError.LoadFailed), presenter.state.value)
    }

    /** Runs [afterFirstList] once, right after the first [ProfileRepository.list] call returns. */
    private fun afterFirstList(afterFirstList: suspend () -> Unit) = object : ProfileRepository by profiles {
        private var done = false

        override suspend fun list(): List<ServerProfile> {
            val result = profiles.list()
            if (!done) {
                done = true
                afterFirstList()
            }
            return result
        }
    }

    @Test
    fun `a server deleted elsewhere between select's read and save is not resurrected`() = runTest {
        val a = profile("a")
        profiles.save(a)
        val presenter = presenter(afterFirstList { profiles.delete(a.id) })
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(ServerListIntent.Select(a.id))
        runCurrent()

        assertTrue(profiles.list().isEmpty())
        assertTrue(events.isEmpty())
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `select saves the fields another screen changed after its first read`() = runTest {
        val a = profile("a", name = "old name")
        val renamed = a.copy(name = "new name", port = 2222)
        profiles.save(a)
        val presenter = presenter(afterFirstList { profiles.save(renamed) })
        runCurrent()

        presenter.onIntent(ServerListIntent.Select(a.id))
        runCurrent()

        assertEquals(renamed.copy(lastUsedAt = now), profiles.list().single())
    }

    @Test
    fun `select marks the server used now, moves it on top and opens the connection`() = runTest {
        profiles.save(profile("a", lastUsedAt = Instant.parse("2026-01-01T00:00:00Z")))
        profiles.save(profile("b"))
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(ServerListIntent.Select(ServerProfileId("b")))
        runCurrent()

        assertEquals(now, profiles.list().single { it.id.value == "b" }.lastUsedAt)
        assertEquals(listOf("b", "a"), presenter.state.value.servers.map { it.id.value })
        assertEquals(listOf<ServerListEvent>(ServerListEvent.OpenProgress(ServerProfileId("b"))), events)
    }

    @Test
    fun `select keeps everything else in the profile`() = runTest {
        val original = profile("a", auth = SshAuth.PrivateKey(SecretRef("k"), SecretRef("p")))
        profiles.save(original)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.Select(original.id))
        runCurrent()

        assertEquals(original.copy(lastUsedAt = now), profiles.list().single())
    }

    @Test
    fun `a failed save shows SaveFailed and does not open the connection`() = runTest {
        profiles.save(profile("a"))
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        profiles.unavailable = true
        presenter.onIntent(ServerListIntent.Select(ServerProfileId("a")))
        runCurrent()

        assertEquals(ServerListError.SaveFailed, presenter.state.value.error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `selecting an unknown server does nothing`() = runTest {
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(ServerListIntent.Select(ServerProfileId("missing")))
        runCurrent()

        assertTrue(events.isEmpty())
        assertTrue(profiles.list().isEmpty())
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `navigation intents become events in order`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.Add)
        presenter.onIntent(ServerListIntent.Edit(ServerProfileId("a")))
        presenter.onIntent(ServerListIntent.OpenKeys)
        presenter.onIntent(ServerListIntent.OpenSettings)

        assertEquals(
            listOf(
                ServerListEvent.OpenForm(null),
                ServerListEvent.OpenForm(ServerProfileId("a")),
                ServerListEvent.OpenKeys,
                ServerListEvent.OpenSettings,
            ),
            presenter.events.take(4).toList(),
        )
    }

    @Test
    fun `an event sent while nobody collects waits for the next collector`() = runTest {
        val presenter = presenter()
        presenter.onIntent(ServerListIntent.Add)

        val first = mutableListOf<ServerListEvent>()
        val job = backgroundScope.launch { presenter.events.collect { first += it } }
        runCurrent()
        job.cancel()
        val second = collectEvents(presenter)
        runCurrent()

        assertEquals(listOf<ServerListEvent>(ServerListEvent.OpenForm(null)), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `request delete asks for confirmation and deletes nothing`() = runTest {
        val a = profile("a")
        profiles.save(a)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        runCurrent()

        assertEquals(a, presenter.state.value.pendingDelete)
        assertEquals(listOf(a), profiles.list())
    }

    @Test
    fun `request delete of an unknown server asks nothing`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(ServerProfileId("missing")))
        runCurrent()

        assertNull(presenter.state.value.pendingDelete)
    }

    @Test
    fun `cancel delete keeps the server`() = runTest {
        val a = profile("a")
        profiles.save(a)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.CancelDelete)
        runCurrent()

        assertNull(presenter.state.value.pendingDelete)
        assertEquals(listOf(a), profiles.list())
    }

    @Test
    fun `confirm without a request deletes nothing`() = runTest {
        profiles.save(profile("a"))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertEquals(1, profiles.list().size)
    }

    @Test
    fun `confirm delete removes the server, its secrets and its recent folders`() = runTest {
        val password = SecretRef("a-password")
        val credential = SecretRef("a-opencode")
        val a = profile("a", auth = SshAuth.Password(password), opencodeCredential = credential)
        val b = profile("b")
        profiles.save(a)
        profiles.save(b)
        putSecret(password)
        putSecret(credential)
        putSecret(SecretRef("b-password"))
        recentFolders.add(a.id, "/srv/project")
        recentFolders.add(b.id, "/srv/other")
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertEquals(listOf(b), profiles.list())
        assertEquals(listOf(b), presenter.state.value.servers)
        assertNull(presenter.state.value.pendingDelete)
        assertFalse(exists(password))
        assertFalse(exists(credential))
        assertTrue(exists(SecretRef("b-password")))
        assertTrue(recentFolders.list(a.id).isEmpty())
        assertEquals(listOf("/srv/other"), recentFolders.list(b.id))
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `an unreadable secret of the deleted server is removed too`() = runTest {
        val password = SecretRef("a-password")
        val a = profile("a", auth = SshAuth.Password(password))
        profiles.save(a)
        secrets.seedUnreadable(password)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertEquals(0, secrets.entryCount)
    }

    @Test
    fun `a private key and passphrase only the deleted server used are removed`() = runTest {
        val key = SecretRef("loose-key")
        val passphrase = SecretRef("loose-passphrase")
        val a = profile("a", auth = SshAuth.PrivateKey(key, passphrase))
        profiles.save(a)
        putSecret(key)
        putSecret(passphrase)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertFalse(exists(key))
        assertFalse(exists(passphrase))
    }

    @Test
    fun `a key in the key index stays when its server is deleted`() = runTest {
        val key = SecretRef("indexed-key")
        val passphrase = SecretRef("indexed-passphrase")
        keyIndex.save(
            SshKeyEntry(
                id = SshKeyId("k1"),
                name = "laptop",
                type = "ssh-ed25519",
                fingerprint = "SHA256:AAAA",
                publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test",
                privateKeyRef = key,
                passphraseRef = passphrase,
            ),
        )
        val a = profile("a", auth = SshAuth.PrivateKey(key, passphrase))
        profiles.save(a)
        putSecret(key)
        putSecret(passphrase)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertTrue(profiles.list().isEmpty())
        assertTrue(exists(key))
        assertTrue(exists(passphrase))
    }

    @Test
    fun `a secret another server still refers to stays`() = runTest {
        val shared = SecretRef("shared-key")
        val credential = SecretRef("shared-opencode")
        val a = profile("a", auth = SshAuth.PrivateKey(shared), opencodeCredential = credential)
        val b = profile("b", auth = SshAuth.Password(SecretRef("b-password")), opencodeCredential = shared)
        val c = profile("c", auth = SshAuth.Password(credential))
        listOf(a, b, c).forEach { profiles.save(it) }
        putSecret(shared)
        putSecret(credential)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertTrue(exists(shared))
        assertTrue(exists(credential))
    }

    @Test
    fun `a failed delete shows DeleteFailed and keeps the server`() = runTest {
        val password = SecretRef("a-password")
        val a = profile("a", auth = SshAuth.Password(password))
        profiles.save(a)
        putSecret(password)
        recentFolders.add(a.id, "/srv/project")
        val presenter = presenter()
        runCurrent()

        profiles.unavailable = true
        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()
        profiles.unavailable = false

        assertEquals(ServerListError.DeleteFailed, presenter.state.value.error)
        assertEquals(listOf(a), profiles.list())
        assertTrue(exists(password), "the secret of a server that is still saved must stay")
        assertEquals(listOf("/srv/project"), recentFolders.list(a.id))
    }

    @Test
    fun `a failed secret cleanup shows DeleteFailed but still forgets the folders`() = runTest {
        val password = SecretRef("a-password")
        val a = profile("a", auth = SshAuth.Password(password))
        profiles.save(a)
        putSecret(password)
        recentFolders.add(a.id, "/srv/project")
        val presenter = presenter()
        runCurrent()

        secrets.unavailable = true
        presenter.onIntent(ServerListIntent.RequestDelete(a.id))
        presenter.onIntent(ServerListIntent.ConfirmDelete)
        runCurrent()

        assertEquals(ServerListError.DeleteFailed, presenter.state.value.error)
        assertTrue(profiles.list().isEmpty())
        assertTrue(recentFolders.list(a.id).isEmpty())
    }

    @Test
    fun `dismiss error clears it`() = runTest {
        profiles.save(profile("a"))
        val presenter = presenter()
        runCurrent()
        profiles.unavailable = true
        presenter.onIntent(ServerListIntent.Select(ServerProfileId("a")))
        runCurrent()

        presenter.onIntent(ServerListIntent.DismissError)

        assertNull(presenter.state.value.error)
    }

    @Test
    fun `the pending server follows the repository and goes when it is deleted elsewhere`() = runTest {
        val a = profile("a")
        profiles.save(a)
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ServerListIntent.RequestDelete(a.id))

        profiles.delete(a.id)
        runCurrent()

        assertNull(presenter.state.value.pendingDelete)
    }

    @Test
    fun `intents never suspend the caller`() = runTest {
        profiles.save(profile("a"))
        val presenter = presenter()
        // No runCurrent: onIntent returns at once even before loading ran.
        presenter.onIntent(ServerListIntent.Select(ServerProfileId("a")))
        presenter.onIntent(ServerListIntent.OpenKeys)

        assertIs<ServerListState>(presenter.state.value)
    }
}
