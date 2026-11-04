package io.github.thewisekd.phopencode.core.storage.secret

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TinkSecretStoreTest {
    private val dir: File = Files.createTempDirectory("secret-store").toFile()
    private val root = File(dir, "secrets")
    private val masterKey = FakeMasterKey()
    private val ref = SecretRef("profile-a-password")
    private val otherRef = SecretRef("profile-b-password")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun newStore() = TinkSecretStore(root, masterKey)

    private suspend fun SecretStore.putText(ref: SecretRef, text: String) = SecretValue.ofText(text).use { put(ref, it) }

    private suspend fun SecretStore.text(ref: SecretRef): String {
        val result = read(ref)
        check(result is SecretReadResult.Found) { "Expected Found, was $result" }
        return result.value.use { it.revealText() }
    }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun valuesAreNotPlaintextOnDisk() = runBlocking<Unit> {
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
        val files = filesUnder(dir)
        assertTrue("nothing was written", files.isNotEmpty())
        files.forEach { file ->
            val bytes = file.readBytes()
            forms.forEach { assertFalse("${file.name} holds the value", bytes.contains(it)) }
        }
    }

    @Test
    fun referenceIdsDoNotAppearOnDisk() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        val id = ref.id.encodeToByteArray()
        filesUnder(dir).forEach { file ->
            assertFalse("${file.name} names the reference", file.name.contains(ref.id))
            assertFalse("${file.name} holds the reference", file.readBytes().contains(id))
        }
    }

    @Test
    fun theKeysetOnDiskIsNoCleartextKeyset() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        val keyset = newStore().keysetFile.readBytes()
        assertThrows(GeneralSecurityException::class.java) {
            TinkProtoKeysetFormat.parseKeyset(keyset, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        }
    }

    @Test
    fun aNewStoreOverTheSameFilesReadsWhatAnEarlierOneStored() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        assertEquals("value", newStore().text(ref))
    }

    @Test
    fun entriesAreUnreadableOnceTheMasterKeyIsGone() = runBlocking<Unit> {
        val first = newStore()
        first.putText(ref, "a")
        first.putText(otherRef, "b")

        // Removing the installation removes its Keystore key; the files may come back from elsewhere.
        masterKey.delete()
        val store = newStore()

        assertEquals(SecretReadResult.Unreadable, store.read(ref))
        assertEquals(SecretReadResult.Unreadable, store.read(otherRef))
        assertEquals(SecretReadResult.NotFound, store.read(SecretRef("never-stored")))
    }

    @Test
    fun afterTheMasterKeyIsGoneANewSecretCanBeStoredAndTheOldOnesStayUnreadable() = runBlocking<Unit> {
        val first = newStore()
        first.putText(ref, "a")
        first.putText(otherRef, "b")
        masterKey.delete()

        val store = newStore()
        store.putText(ref, "new")

        assertEquals("new", store.text(ref))
        assertEquals(SecretReadResult.Unreadable, store.read(otherRef))
        assertTrue(masterKey.exists)
        assertEquals("new", newStore().text(ref))
    }

    @Test
    fun aFailedWriteAfterANewMasterKeyLeavesTheStoreUsable() = runBlocking<Unit> {
        val probe = File(dir, "probe").apply { mkdir(); setWritable(false) }
        val permissionsApply = !probe.canWrite()
        probe.setWritable(true)
        probe.delete()
        assumeTrue("the test runs as a user that can write anywhere", permissionsApply)

        newStore().putText(ref, "a")
        masterKey.delete()
        // The new master key exists, then writing the new keyset fails once.
        masterKey.afterCreate = {
            masterKey.afterCreate = {}
            root.setWritable(false)
        }
        val store = newStore()
        try {
            assertThrows(IOException::class.java) { runBlocking { store.putText(otherRef, "b") } }
            assertTrue("the new master key was not made", masterKey.exists)
        } finally {
            root.setWritable(true)
        }

        store.putText(otherRef, "b")

        assertEquals("b", store.text(otherRef))
        assertEquals("b", newStore().text(otherRef))
        assertEquals(SecretReadResult.Unreadable, newStore().read(ref))
    }

    @Test
    fun aStoreThatAlreadyUnlockedItsKeysetStillReadsWhileTheKeystoreIsUnavailable()= runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        masterKey.failing = true
        assertEquals("value", store.text(ref))
    }

    @Test
    fun anUnavailableKeystoreIsAnIOExceptionAndDestroysNothing() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        masterKey.failing = true
        val store = newStore()

        assertThrows(IOException::class.java) { runBlocking { store.read(ref) } }
        assertThrows(IOException::class.java) { runBlocking { store.putText(otherRef, "other") } }

        masterKey.failing = false
        assertEquals("value", store.text(ref))
        assertEquals(SecretReadResult.NotFound, store.read(otherRef))
    }

    @Test
    fun aKeysetTheMasterKeyCannotOpenIsAnIOExceptionUntilDeleteAll() = runBlocking<Unit> {
        newStore().putText(ref, "value")
        val keyset = newStore().keysetFile
        val sealed = keyset.readBytes()
        // A master key is present, but not the one that sealed the keyset.
        masterKey.replaceWithAnotherKey()
        val store = newStore()

        assertThrows(IOException::class.java) { runBlocking { store.read(ref) } }
        assertThrows(IOException::class.java) { runBlocking { store.putText(otherRef, "other") } }
        assertThrows(IOException::class.java) { runBlocking { newStore().read(ref) } }
        // Not discarded: the keyset and the entry are still there, byte for byte.
        assertTrue(keyset.readBytes().contentEquals(sealed))
        assertTrue(store.entryFile(ref).exists())
        assertFalse(store.entryFile(otherRef).exists())

        store.deleteAll()
        assertEquals(SecretReadResult.NotFound, store.read(ref))
        store.putText(ref, "again")
        assertEquals("again", newStore().text(ref))
    }

    @Test
    fun anEntryMovedToAnotherReferenceIsUnreadable() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        val target = store.entryFile(otherRef)
        store.entryFile(ref).copyTo(target)
        assertEquals(SecretReadResult.Unreadable, store.read(otherRef))
    }

    @Test
    fun aDamagedEntryIsUnreadable() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        store.entryFile(ref).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(SecretReadResult.Unreadable, store.read(ref))
        store.entryFile(ref).writeBytes(ByteArray(0))
        assertEquals(SecretReadResult.Unreadable, store.read(ref))
    }

    @Test
    fun anEntryWithoutAnyKeysetIsUnreadable() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        assertTrue(store.keysetFile.delete())
        assertEquals(SecretReadResult.Unreadable, newStore().read(ref))
    }

    @Test
    fun referencesThatLookLikePathsStayInsideTheStore() = runBlocking<Unit> {
        val store = newStore()
        val refs = listOf("../escape", "/abs/path", "a/b", "", ".", "..", "x".repeat(4096)).map(::SecretRef)
        refs.forEachIndexed { index, it -> store.putText(it, "v$index") }
        refs.forEachIndexed { index, it -> assertEquals("v$index", store.text(it)) }
        val outside = dir.listFiles()!!.map { it.name }
        assertEquals(listOf("secrets"), outside)
        refs.forEach { assertTrue(store.entryFile(it).canonicalPath.startsWith(root.canonicalPath + File.separator)) }
    }

    @Test
    fun deleteAllRemovesTheKeysetAndTheMasterKey() = runBlocking<Unit> {
        val store = newStore()
        store.putText(ref, "value")
        store.deleteAll()
        assertFalse(store.keysetFile.exists())
        assertFalse(masterKey.exists)
        assertTrue(filesUnder(dir).isEmpty())
    }

    @Test
    fun readingCreatesNoKeyAndNoFile() = runBlocking<Unit> {
        val store = newStore()
        assertEquals(SecretReadResult.NotFound, store.read(ref))
        store.delete(ref)
        assertFalse(masterKey.exists)
        assertTrue(filesUnder(dir).isEmpty())
    }
}
