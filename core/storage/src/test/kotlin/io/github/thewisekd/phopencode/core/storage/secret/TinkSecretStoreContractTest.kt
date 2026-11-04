package io.github.thewisekd.phopencode.core.storage.secret

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.SecretStoreHarness
import io.github.thewisekd.phopencode.core.testing.contract.secretStoreContract
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The shared SecretStore contract against the real store, with a JVM stand-in for the Keystore. */
@RunWith(Parameterized::class)
class TinkSecretStoreContractTest(private val case: ContractCase) {
    @Test
    fun contract() = case.run()

    companion object {
        private val base: File = Files.createTempDirectory("secret-contract").toFile()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<ContractCase> = secretStoreContract { JvmHarness(Files.createTempDirectory(base.toPath(), "case").toFile()) }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            base.deleteRecursively()
        }
    }
}

private class JvmHarness(private val dir: File) : SecretStoreHarness {
    private val root = File(dir, "secrets")
    override val store = TinkSecretStore(root, FakeMasterKey())

    override suspend fun givenUnreadable(ref: SecretRef) {
        // An entry written by another installation, whose master key this one never had.
        val other = TinkSecretStore(File(dir, "other"), FakeMasterKey())
        SecretValue.ofText("from another installation").use { other.put(ref, it) }
        val target = store.entryFile(ref)
        target.parentFile!!.mkdirs()
        Files.copy(other.entryFile(ref).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun setReachable(reachable: Boolean) = StorageSwitch.set(root, reachable)
}

/**
 * Makes a store's directory unreachable by parking it and putting a plain file in its place, and
 * back. Works whether or not the store has made its directory yet, and switching to the state it
 * is already in changes nothing. The androidTest source set keeps an identical copy.
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
