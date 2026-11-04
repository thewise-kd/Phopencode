package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSettingsRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
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

    private fun keyEntry(publicKey: String) = SshKeyEntry(
        id = SshKeyId("k"),
        name = "Laptop",
        type = "ssh-ed25519",
        fingerprint = "SHA256:fingerprint",
        publicKey = publicKey,
        privateKeyRef = SecretRef("key-ref"),
    )

    @Test
    fun `a key entry refuses private key files in place of the public key`() {
        // Assembled at runtime, so that no file in the public repository looks like a private key.
        val end = listOf("PRIVATE", "KEY-----").joinToString(" ")
        val refused = listOf(
            listOf("-----BEGIN OPENSSH $end", "b3BlbnNzaC1rZXktdjEAAAAA", "-----END OPENSSH $end"),
            listOf("-----BEGIN RSA $end", "MIIEowIBAAKCAQEA", "-----END RSA $end"),
            listOf("-----BEGIN $end", "MIGHAgEAMBMGByqGSM49", "-----END $end"),
            listOf(
                "PuTTY-User-Key-File-3: ssh-ed25519",
                "Encryption: none",
                "Public-Lines: 1",
                "AAAAC3NzaC1lZDI1NTE5AAAA",
                "Private-Lines: 1",
                "AAAAIBkeyZ",
            ),
        )
        refused.forEach { lines ->
            assertFailsWith<IllegalArgumentException>(lines.first()) { keyEntry(lines.joinToString("\n")) }
            // Joined onto one line, the headers still give it away.
            assertFailsWith<IllegalArgumentException>(lines.first()) { keyEntry(lines.joinToString(" ")) }
        }
        assertFailsWith<IllegalArgumentException> { keyEntry("Private-Lines: 1 AAAA") }
    }

    @Test
    fun `a public key is one line of type, base64 blob and an optional comment`() {
        listOf(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA user@phone\nssh-rsa AAAAB3",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA\r",
            "ssh-ed25519",
            "ssh-ed25519 not*base64",
            "",
        ).forEach { assertFailsWith<IllegalArgumentException>(it) { keyEntry(it) } }
        keyEntry("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA")
        keyEntry("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY= user@phone")
        keyEntry("sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t")
    }

    @Test
    fun `a comment may say anything on its line, private key included`() {
        listOf(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA my private key",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA PRIVATE KEY----- Private-Lines: 1",
            "ssh-rsa AAAAB3NzaC1yc2E= laptop  -----BEGIN",
        ).forEach { assertEquals(it, keyEntry(it).publicKey) }
    }

    @Test
    fun `printing a key entry shows neither its name, fingerprint nor public key`() {
        val printed = keyEntry("ssh-ed25519 AAAAkeydata user@phone").toString()
        listOf("Laptop", "SHA256:fingerprint", "AAAAkeydata", "user@phone").forEach {
            assertFalse(it in printed, "printed: $printed")
        }
    }

    @Test
    fun `unavailable key and folder stores refuse reads and writes`() = runTest {
        val keys = FakeSshKeyIndex().apply { unavailable = true }
        val folders = FakeRecentFoldersStore().apply { unavailable = true }
        assertFailsWith<IOException> { keys.list() }
        assertFailsWith<IOException> { folders.add(ServerProfileId("a"), "/srv") }
    }
}
