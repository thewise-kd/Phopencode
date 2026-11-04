package io.github.thewisekd.phopencode.core.storage.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4.1's acceptance: values are not plaintext on disk, and they are unreadable once the
 * installation is gone. Runs on a device, against the real Android Keystore.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    private val support = KeystoreTestSupport("store")
    private val alias = support.newAlias()
    private val root = support.newDir()
    private val ref = SecretRef("profile-a-password")
    private val otherRef = SecretRef("profile-b-password")

    @After
    fun cleanUp() = support.cleanUp()

    private fun newStore(keyAlias: String = alias, dir: File = root) = TinkSecretStore(dir, AndroidKeystoreMasterKey(keyAlias))

    private suspend fun SecretStore.putText(ref: SecretRef, text: String) = SecretValue.ofText(text).use { put(ref, it) }

    private suspend fun SecretStore.text(ref: SecretRef): String {
        val result = read(ref)
        check(result is SecretReadResult.Found) { "Expected Found, was $result" }
        return result.value.use { it.revealText() }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun valuesAreNotPlaintextAnywhereInTheAppData() = runBlocking<Unit> {
        // Assembled at runtime, so that the marker is no literal value in the repository.
        val marker = listOf("marker", "Q7", "plaintext", "Zx").joinToString("-")
        val store = newStore()
        store.putText(ref, marker)
        store.putText(otherRef, marker + marker)

        val forms = listOf(
            marker.encodeToByteArray(),
            marker.toByteArray(Charsets.UTF_16LE),
            marker.toByteArray(Charsets.UTF_16BE),
            Base64.getEncoder().encode(marker.encodeToByteArray()).copyOf(12),
        )
        val files = support.context.dataDir.walkTopDown().filter { it.isFile && it.canRead() }.toList()
        assertTrue("the store wrote nothing", files.any { it.path.startsWith(root.path) })
        files.forEach { file ->
            val bytes = file.readBytes()
            forms.forEach { assertFalse("${file.name} holds the value", bytes.contains(it)) }
        }
        assertEquals(marker, store.text(ref))
    }

    @Test
    fun theMasterKeyLivesInTheAndroidKeystoreAndCannotBeExported() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        val keyStore = KeystoreTestSupport.androidKeyStore()
        assertTrue(keyStore.containsAlias(alias))
        val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
        assertNull("the key material left the Keystore", entry.secretKey.encoded)
    }

    @Test
    fun theKeysetOnDiskIsNoCleartextKeyset() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        val keyset = store.keysetFile.readBytes()
        assertThrows(GeneralSecurityException::class.java) {
            TinkProtoKeysetFormat.parseKeyset(keyset, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        }
    }

    @Test
    fun aNewStoreReadsWhatAnEarlierOneStored() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        assertEquals("value", newStore().text(ref))
    }

    @Test
    fun entriesAreUnreadableOnceTheInstallationsKeystoreKeyIsGone() = runBlocking<Unit> {
        newStore().putText(ref, "a")
        newStore().putText(otherRef, "b")

        // Uninstalling deletes the app's Keystore keys; the files are what could survive elsewhere.
        KeystoreTestSupport.androidKeyStore().deleteEntry(alias)
        val store = newStore()

        assertEquals(SecretReadResult.Unreadable, store.read(ref))
        assertEquals(SecretReadResult.Unreadable, store.read(otherRef))

        store.putText(ref, "new")
        assertEquals("new", newStore().text(ref))
        assertEquals(SecretReadResult.Unreadable, newStore().read(otherRef))
    }

    @Test
    fun filesRestoredIntoAnotherInstallationAreUnreadable() = runBlocking<Unit> {
        newStore().putText(ref, "value")

        // Another installation: its own Keystore key, and this installation's files copied in.
        val restored = support.newDir()
        root.copyRecursively(restored)
        val other = newStore(keyAlias = support.newAlias(), dir = restored)

        assertEquals(SecretReadResult.Unreadable, other.read(ref))
        other.putText(otherRef, "fresh")
        assertEquals("fresh", other.text(otherRef))
        assertEquals(SecretReadResult.Unreadable, other.read(ref))
    }

    @Test
    fun deleteAllRemovesTheFilesAndTheKeystoreKey() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        store.deleteAll()
        assertFalse(KeystoreTestSupport.androidKeyStore().containsAlias(alias))
        assertTrue(root.walkTopDown().none { it.isFile })
        assertEquals(SecretReadResult.NotFound, store.read(ref))
    }

    @Test
    fun theAppStoreKeepsItsFilesOutOfBackups() = runBlocking<Unit> {
        val store = androidSecretStore(support.context)
        val dir = File(support.context.noBackupFilesDir, "secrets")
        try {
            store.putText(ref, "value")
            assertEquals("value", store.text(ref))
            assertTrue(dir.walkTopDown().any { it.isFile })
        } finally {
            store.deleteAll()
        }
        assertTrue(dir.walkTopDown().none { it.isFile })
    }
}
