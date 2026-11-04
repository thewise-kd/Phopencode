package io.github.thewisekd.phopencode.core.storage.repo

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.SettingsHarness
import io.github.thewisekd.phopencode.core.testing.contract.StoragePart
import io.github.thewisekd.phopencode.core.testing.contract.knownHostsStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.profileRepositoryContract
import io.github.thewisekd.phopencode.core.testing.contract.settingsRepositoryContract
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The P1.4 contracts, run against the DataStore repositories on real files. Parameters are names,
 * not cases: a case is built inside the sandbox, where its factory can reach this test's folder.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RepositoryContractsTest(private val contract: String, private val case: String) {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    @Test
    fun run() {
        cases(contract) { stores }.single { it.name == case }.run()
    }

    private class Harness(stores: TestStores) : SettingsHarness {
        private val secretStore = FakeSecretStore()
        private val profileStore = stores.profiles().store
        private val knownHostStore = stores.knownHosts().store
        private val settingsStore = stores.settings().store
        private val profileRepository = DataStoreProfileRepository(profileStore)
        private val knownHostsRepository = DataStoreKnownHostsStore(knownHostStore)

        override val secrets = secretStore
        override val profiles = profileRepository
        override val knownHosts = knownHostsRepository
        override val settings = DataStoreSettingsRepository(settingsStore, secretStore, profileRepository, knownHostsRepository)

        override suspend fun givenUnreadableSecret(ref: SecretRef) {
            secretStore.seedUnreadable(ref)
        }

        override suspend fun setFailingPart(part: StoragePart?) {
            secretStore.unavailable = part == StoragePart.Secrets
            profileStore.failing = part == StoragePart.Profiles
            knownHostStore.failing = part == StoragePart.KnownHosts
            settingsStore.failing = part == StoragePart.Settings
        }
    }

    companion object {
        private fun cases(contract: String, stores: () -> TestStores): List<ContractCase> = when (contract) {
            "profiles" -> profileRepositoryContract { DataStoreProfileRepository(stores().profiles().store) }
            "knownHosts" -> knownHostsStoreContract { DataStoreKnownHostsStore(stores().knownHosts().store) }
            "settings" -> settingsRepositoryContract { Harness(stores()) }
            else -> error("Unknown contract: $contract")
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}: {1}")
        fun parameters(): List<Array<Any>> = listOf("profiles", "knownHosts", "settings").flatMap { contract ->
            cases(contract) { error("Listing cases opens no store") }.map { arrayOf<Any>(contract, it.name) }
        }
    }
}
