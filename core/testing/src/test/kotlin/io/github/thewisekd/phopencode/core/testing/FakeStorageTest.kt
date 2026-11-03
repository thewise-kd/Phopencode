package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSettingsRepository
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** What the storage fakes promise beyond the contracts. */
class FakeStorageTest {
    private val ref = SecretRef("ref")

    @Test
    fun `printing the secret store shows no secret`() = runTest {
        val store = FakeSecretStore()
        SecretValue.ofText("hunter2").use { store.put(ref, it) }
        assertFalse("hunter2" in store.toString())
        assertEquals(1, store.entryCount)
    }

    @Test
    fun `each read hands out its own value`() = runTest {
        val store = FakeSecretStore()
        SecretValue.ofText("value").use { store.put(ref, it) }
        val first = assertIs<SecretReadResult.Found>(store.read(ref)).value
        val second = assertIs<SecretReadResult.Found>(store.read(ref)).value
        first.close()
        assertEquals("value", second.use { it.revealText() })
    }

    @Test
    fun `a standalone settings repository owns empty stores`() = runTest {
        FakeSettingsRepository().deleteAllData()
    }

    @Test
    fun `a failing part leaves the other parts deleted`() = runTest {
        val secrets = FakeSecretStore()
        val profiles = FakeProfileRepository()
        val knownHosts = FakeKnownHostsStore()
        SecretValue.ofText("value").use { secrets.put(ref, it) }
        knownHosts.unavailable = true
        assertFailsWith<IOException> { FakeSettingsRepository(secrets, profiles, knownHosts).deleteAllData() }
        assertEquals(0, secrets.entryCount)
    }

    @Test
    fun `an unavailable repository refuses reads and writes`() = runTest {
        val profiles = FakeProfileRepository().apply { unavailable = true }
        assertFailsWith<IOException> { profiles.list() }
    }
}
