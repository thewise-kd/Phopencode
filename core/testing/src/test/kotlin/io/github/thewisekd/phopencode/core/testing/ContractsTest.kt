package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.catalogApiContract
import io.github.thewisekd.phopencode.core.testing.contract.eventApiContract
import io.github.thewisekd.phopencode.core.testing.contract.fsApiContract
import io.github.thewisekd.phopencode.core.testing.contract.hostKeyVerifierContract
import io.github.thewisekd.phopencode.core.testing.contract.interactionApiContract
import io.github.thewisekd.phopencode.core.testing.contract.knownHostsStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.messageApiContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import io.github.thewisekd.phopencode.core.testing.contract.profileRepositoryContract
import io.github.thewisekd.phopencode.core.testing.contract.recentFoldersStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.remoteExecContract
import io.github.thewisekd.phopencode.core.testing.contract.secretStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.sessionApiContract
import io.github.thewisekd.phopencode.core.testing.contract.settingsRepositoryContract
import io.github.thewisekd.phopencode.core.testing.contract.sftpClientContract
import io.github.thewisekd.phopencode.core.testing.contract.sshConnectorContract
import io.github.thewisekd.phopencode.core.testing.contract.sshKeyIndexContract
import io.github.thewisekd.phopencode.core.testing.contract.sshSessionContract
import io.github.thewisekd.phopencode.core.testing.contract.vcsApiContract
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every contract against the fakes. Real implementations run the same lists. */
class ContractsTest {
    private fun List<ContractCase>.asTests() = map { DynamicTest.dynamicTest(it.name) { it.run() } }

    @TestFactory
    fun opencodeClient() = opencodeClientContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun eventApi() = eventApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun sessionApi() = sessionApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun messageApi() = messageApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun interactionApi() = interactionApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun catalogApi() = catalogApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun vcsApi() = vcsApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun fsApi() = fsApiContract { FakeApiHarness() }.asTests()

    @TestFactory
    fun hostKeyVerifier() = hostKeyVerifierContract { FakeHostKeyVerifierHarness() }.asTests()

    @TestFactory
    fun sshConnector() = sshConnectorContract { FakeSshConnectorHarness() }.asTests()

    @TestFactory
    fun sshSession() = sshSessionContract { FakeSshSessionHarness() }.asTests()

    @TestFactory
    fun remoteExec() = remoteExecContract { FakeRemoteExecHarness() }.asTests()

    @TestFactory
    fun sftpClient() = sftpClientContract { FakeSftpHarness() }.asTests()

    @TestFactory
    fun secretStore() = secretStoreContract { FakeSecretStoreHarness() }.asTests()

    @TestFactory
    fun profileRepository() = profileRepositoryContract { FakeProfileRepository() }.asTests()

    @TestFactory
    fun knownHostsStore() = knownHostsStoreContract { FakeKnownHostsStore() }.asTests()

    @TestFactory
    fun sshKeyIndex() = sshKeyIndexContract { FakeSshKeyIndex() }.asTests()

    @TestFactory
    fun recentFoldersStore() = recentFoldersStoreContract { FakeRecentFoldersStore() }.asTests()

    @TestFactory
    fun settingsRepository() = settingsRepositoryContract { FakeSettingsHarness() }.asTests()
}
