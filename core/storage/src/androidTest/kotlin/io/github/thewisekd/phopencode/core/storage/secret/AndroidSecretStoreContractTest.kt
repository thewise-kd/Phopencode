package io.github.thewisekd.phopencode.core.storage.secret

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.SecretStoreHarness
import io.github.thewisekd.phopencode.core.testing.contract.secretStoreContract
import java.io.File
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The shared SecretStore contract against the real store and the real Android Keystore. */
@RunWith(Parameterized::class)
class AndroidSecretStoreContractTest(private val case: ContractCase) {
    @Test
    fun contract() = case.run()

    companion object {
        private val support = KeystoreTestSupport("contract")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<ContractCase> = secretStoreContract { KeystoreHarness(support) }

        @JvmStatic
        @AfterClass
        fun cleanUp() = support.cleanUp()
    }
}

private class KeystoreHarness(private val support: KeystoreTestSupport) : SecretStoreHarness {
    private val root: File = support.newDir()
    override val store = TinkSecretStore(root, AndroidKeystoreMasterKey(support.newAlias()))

    override suspend fun givenUnreadable(ref: SecretRef) {
        // An entry written by another installation, whose Keystore key this one never had.
        val other = TinkSecretStore(support.newDir(), AndroidKeystoreMasterKey(support.newAlias()))
        SecretValue.ofText("from another installation").use { other.put(ref, it) }
        other.entryFile(ref).copyTo(store.entryFile(ref), overwrite = true)
    }

    override suspend fun setReachable(reachable: Boolean) = StorageSwitch.set(root, reachable)
}
