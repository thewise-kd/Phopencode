package io.github.thewisekd.phopencode.core.storage.secret

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test helper that makes a store unreachable, as the contract harnesses use it. */
class StorageSwitchTest {
    private val base: File = Files.createTempDirectory("storage-switch").toFile()

    // As on the emulator: the store's parent directories do not exist until the store writes.
    private val root = File(base, "not-yet/created/secrets")
    private val ref = SecretRef("ref")

    @After
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun aFreshStoreWithoutAnyDirectoryCanBeMadeUnreachableAndBack() = runBlocking<Unit> {
        val store = TinkSecretStore(root, FakeMasterKey())

        StorageSwitch.set(root, reachable = false)

        assertTrue(root.isFile)
        assertThrows(IOException::class.java) { runBlocking { SecretValue.ofText("v").use { store.put(ref, it) } } }
        assertThrows(IOException::class.java) { runBlocking { store.read(ref) } }
        assertThrows(IOException::class.java) { runBlocking { store.delete(ref) } }
        assertThrows(IOException::class.java) { runBlocking { store.deleteAll() } }

        StorageSwitch.set(root, reachable = true)

        assertFalse(root.exists())
        assertEquals(SecretReadResult.NotFound, store.read(ref))
        SecretValue.ofText("v").use { store.put(ref, it) }
        assertTrue(store.read(ref) is SecretReadResult.Found)
    }

    @Test
    fun anExistingDirectoryIsParkedAndRestoredWithItsContent() {
        root.mkdirs()
        File(root, "content").writeText("kept")

        StorageSwitch.set(root, reachable = false)
        assertTrue(root.isFile)

        StorageSwitch.set(root, reachable = true)
        assertTrue(root.isDirectory)
        assertEquals("kept", File(root, "content").readText())
        assertFalse(File(root.parentFile, root.name + ".parked").exists())
    }

    @Test
    fun switchingToTheCurrentStateChangesNothing() {
        root.mkdirs()
        File(root, "content").writeText("kept")

        StorageSwitch.set(root, reachable = true)
        assertEquals("kept", File(root, "content").readText())

        StorageSwitch.set(root, reachable = false)
        StorageSwitch.set(root, reachable = false)
        StorageSwitch.set(root, reachable = true)
        StorageSwitch.set(root, reachable = true)

        assertEquals("kept", File(root, "content").readText())
    }
}
