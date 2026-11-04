package io.github.thewisekd.phopencode.core.storage.secret

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real Keystore keys and directories for one test class, all removed by [cleanUp]. */
internal class KeystoreTestSupport(name: String) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val base: File = File(context.noBackupFilesDir, "secret-test-$name-${UUID.randomUUID()}")
    private val aliases = CopyOnWriteArrayList<String>()

    /** A fresh Keystore alias, as a separate installation would have. */
    fun newAlias(): String = "phopencode.secret.test.${UUID.randomUUID()}".also(aliases::add)

    fun newDir(): File = File(base, UUID.randomUUID().toString())

    fun cleanUp() {
        base.deleteRecursively()
        val keyStore = androidKeyStore()
        aliases.forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
    }

    companion object {
        fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
}

/**
 * Makes a store's directory unreachable by parking it and putting a plain file in its place, and
 * back. Works whether or not the store has made its directory yet, and switching to the state it
 * is already in changes nothing. The unit test source set keeps an identical copy, tested by
 * StorageSwitchTest there.
 */
internal object StorageSwitch {
    fun set(root: File, reachable: Boolean) {
        val parent = checkNotNull(root.parentFile) { "root needs a parent" }
        val parked = File(parent, root.name + ".parked")
        if (reachable) {
            if (root.isFile) check(root.delete()) { "cannot remove the placeholder" }
            if (parked.exists()) check(parked.renameTo(root)) { "cannot restore the parked directory" }
        } else {
            if (root.isFile) return
            parent.mkdirs()
            check(parent.isDirectory) { "cannot create the parent directory" }
            if (root.exists()) check(root.renameTo(parked)) { "cannot park the directory" }
            check(root.createNewFile()) { "cannot create the placeholder" }
        }
    }
}
