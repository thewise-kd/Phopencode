package io.github.thewisekd.phopencode.core.storage.repo

import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the known hosts file reads back. It fails closed: a file it cannot fully read stops every
 * check with [IOException] rather than reporting a host as unknown, which would ask the user to
 * approve whatever key the server presents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class KnownHostsFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    private val endpoint = HostEndpoint("Server.Invalid", 2222)
    private val other = HostEndpoint("other.invalid", 22)
    private val key = HostKeyFingerprint("SHA256:AbC+/def")
    private val otherKey = HostKeyFingerprint("SHA256:xyz")

    @Test
    fun `approved keys survive reopening the file, exactly as given`() = runTest {
        val first = stores.knownHosts()
        DataStoreKnownHostsStore(first.store).apply {
            approve(endpoint, key)
            approve(other, otherKey)
        }

        val reopened = DataStoreKnownHostsStore(stores.reopen(first) { stores.knownHosts(it) }.store)

        assertEquals(key, reopened.approved(HostEndpoint("server.invalid", 2222)))
        assertEquals(otherKey, reopened.approved(other))
    }

    @Test
    fun `a file that is not a known hosts file fails every call and is left as it was`() = runTest {
        val file = written("not json at all {")
        val store = DataStoreKnownHostsStore(stores.knownHosts(file).store)

        assertUnreadable(store)
        assertEquals("not json at all {", file.readText())
    }

    @Test
    fun `one malformed entry makes the whole file unreadable`() = runTest {
        val file = written(
            """{"version":1,"hosts":[{"host":"other.invalid","port":22,"fingerprint":"SHA256:xyz"},
            {"host":"server.invalid","port":"x","fingerprint":"SHA256:abc"}]}""",
        )

        assertUnreadable(DataStoreKnownHostsStore(stores.knownHosts(file).store))
    }

    @Test
    fun `two different keys for one endpoint make the file unreadable`() = runTest {
        val file = written(
            """{"version":1,"hosts":[{"host":"server.invalid","port":22,"fingerprint":"SHA256:abc"},
            {"host":"SERVER.invalid","port":22,"fingerprint":"SHA256:xyz"}]}""",
        )

        assertUnreadable(DataStoreKnownHostsStore(stores.knownHosts(file).store))
    }

    @Test
    fun `clearing an unreadable file makes it usable again`() = runTest {
        val store = DataStoreKnownHostsStore(stores.knownHosts(written("[]")).store)

        store.clear()

        assertEquals(null, store.approved(endpoint))
        assertEquals(HostKeyUpdate.Stored, store.approve(endpoint, key))
    }

    @Test
    fun `deleteAllData makes an unreadable file usable again`() = runTest {
        val store = DataStoreKnownHostsStore(stores.knownHosts(written("not json at all {")).store)
        val settings = DataStoreSettingsRepository(
            stores.settings().store,
            FakeSecretStore(),
            DataStoreProfileRepository(stores.profiles().store),
            store,
            DataStoreSshKeyIndex(stores.keyIndex().store),
            DataStoreRecentFoldersStore(stores.recentFolders().store),
        )

        settings.deleteAllData()

        assertEquals(null, store.approved(endpoint))
        assertEquals(HostKeyUpdate.Stored, store.approve(endpoint, key))
    }

    @Test
    fun `keys a later version added are ignored`() = runTest {
        val file = written(
            """{"version":9,"future":1,"hosts":[{"host":"server.invalid","port":2222,"fingerprint":"SHA256:abc","x":0}]}""",
        )

        assertEquals(HostKeyFingerprint("SHA256:abc"), DataStoreKnownHostsStore(stores.knownHosts(file).store).approved(endpoint))
    }

    private fun written(text: String): File = stores.newFile(TestStores.KNOWN_HOSTS).apply { writeText(text) }

    private suspend fun assertUnreadable(store: DataStoreKnownHostsStore) {
        assertFailsWith<IOException> { store.approved(endpoint) }
        assertFailsWith<IOException> { store.check(endpoint, key) }
        assertFailsWith<IOException> { store.approve(endpoint, key) }
        assertFailsWith<IOException> { store.replace(endpoint, key, otherKey) }
        assertFailsWith<IOException> { store.delete(endpoint) }
    }
}
