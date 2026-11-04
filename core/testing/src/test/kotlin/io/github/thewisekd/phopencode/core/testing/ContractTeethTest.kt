package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.testing.contract.messageApiContract
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.contract.ApiHarness
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.RemoteExecHarness
import io.github.thewisekd.phopencode.core.testing.contract.SecretStoreHarness
import io.github.thewisekd.phopencode.core.testing.contract.SettingsHarness
import io.github.thewisekd.phopencode.core.testing.contract.SshConnectorHarness
import io.github.thewisekd.phopencode.core.testing.contract.knownHostsStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import io.github.thewisekd.phopencode.core.testing.contract.recentFoldersStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.remoteExecContract
import io.github.thewisekd.phopencode.core.testing.contract.secretStoreContract
import io.github.thewisekd.phopencode.core.testing.contract.settingsRepositoryContract
import io.github.thewisekd.phopencode.core.testing.contract.sshConnectorContract
import io.github.thewisekd.phopencode.core.testing.contract.sshKeyIndexContract
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transform

/**
 * A contract that passes everything proves nothing, so each test here breaks one promise on purpose
 * and names the case that must catch it.
 */
class ContractTeethTest {
    private fun List<ContractCase>.failing(): Set<String> =
        filter { runCatching { it.run() }.exceptionOrNull() is AssertionError }.map { it.name }.toSet()

    private fun assertCaught(expected: String, cases: List<ContractCase>) {
        val failing = cases.failing()
        assert(expected in failing) { "<$expected> must fail, but these did: $failing" }
    }

    /** Overwrites whatever is approved. */
    private class OverwritingApprove(private val real: KnownHostsStore = FakeKnownHostsStore()) : KnownHostsStore by real {
        override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate {
            real.delete(endpoint)
            return real.approve(endpoint, fingerprint)
        }
    }

    /** Does not check that [expected] is still approved. */
    private class UncheckedReplace(private val real: KnownHostsStore = FakeKnownHostsStore()) : KnownHostsStore by real {
        override suspend fun replace(
            endpoint: HostEndpoint,
            expected: HostKeyFingerprint,
            replacement: HostKeyFingerprint,
        ): HostKeyUpdate {
            real.delete(endpoint)
            return real.approve(endpoint, replacement)
        }
    }

    @Test
    fun `approve that overwrites a different key is caught`() {
        assertCaught("approve never overwrites a different approved key", knownHostsStoreContract { OverwritingApprove() })
    }

    @Test
    fun `replace that ignores the expected key is caught`() {
        val cases = knownHostsStoreContract { UncheckedReplace() }
        assertCaught("replace changes nothing when another key is approved", cases)
        assertCaught("replace changes nothing when no key is approved", cases)
    }

    @Test
    fun `deleteAll that skips unreadable entries is caught`() {
        class Skipping(private val fake: FakeSecretStoreHarness = FakeSecretStoreHarness()) : SecretStoreHarness by fake {
            private val unreadable = mutableListOf<SecretRef>()

            override val store: SecretStore = object : SecretStore by fake.store {
                override suspend fun deleteAll() {
                    fake.store.deleteAll()
                    unreadable.forEach { fake.givenUnreadable(it) }
                }
            }

            override suspend fun givenUnreadable(ref: SecretRef) {
                unreadable += ref
                fake.givenUnreadable(ref)
            }
        }
        assertCaught("deleteAll removes every entry, also unreadable ones", secretStoreContract { Skipping() })
    }

    @Test
    fun `deleteAllData that keeps the profiles is caught`() {
        class KeepsProfiles(val fake: FakeSettingsHarness = FakeSettingsHarness()) :
            SettingsHarness by fake {
            override val settings: SettingsRepository = object : SettingsRepository by fake.settings {
                override suspend fun deleteAllData() {
                    val kept = fake.profiles.list()
                    fake.settings.deleteAllData()
                    kept.forEach { fake.profiles.save(it) }
                }
            }
        }
        assertCaught(DELETE_ALL_DATA, settingsRepositoryContract { KeepsProfiles() })
    }

    @Test
    fun `deleteAllData that keeps the key index or the recent folders is caught`() {
        class Keeps(private val keepKeys: Boolean, val fake: FakeSettingsHarness = FakeSettingsHarness()) :
            SettingsHarness by fake {
            override val settings: SettingsRepository = object : SettingsRepository by fake.settings {
                override suspend fun deleteAllData() {
                    val profile = ServerProfileId("a")
                    val keys = fake.keyIndex.list()
                    val folders = fake.recentFolders.list(profile)
                    fake.settings.deleteAllData()
                    if (keepKeys) {
                        keys.forEach { fake.keyIndex.save(it) }
                    } else {
                        folders.reversed().forEach { fake.recentFolders.add(profile, it) }
                    }
                }
            }
        }
        assertCaught(DELETE_ALL_DATA, settingsRepositoryContract { Keeps(keepKeys = true) })
        assertCaught(DELETE_ALL_DATA, settingsRepositoryContract { Keeps(keepKeys = false) })
    }

    @Test
    fun `a key index that drops the passphrase reference is caught`() {
        val cases = sshKeyIndexContract {
            val real = FakeSshKeyIndex()
            object : SshKeyIndex by real {
                override suspend fun save(entry: SshKeyEntry) = real.save(entry.copy(passphraseRef = null))
            }
        }
        assertCaught("every field of an entry reads back as saved", cases)
    }

    @Test
    fun `a key index that does not sort is caught`() {
        val cases = sshKeyIndexContract {
            val real = FakeSshKeyIndex()
            object : SshKeyIndex by real {
                override suspend fun list() = real.list().reversed()
            }
        }
        assertCaught("lists are sorted by name, then by ID", cases)
    }

    @Test
    fun `recent folders that are unbounded or oldest first are caught`() {
        val unbounded = recentFoldersStoreContract {
            val real = FakeRecentFoldersStore()
            object : RecentFoldersStore by real {
                private val all = mutableMapOf<ServerProfileId, MutableList<String>>()

                override suspend fun add(profile: ServerProfileId, path: String) {
                    real.add(profile, path)
                    all.getOrPut(profile) { mutableListOf() }.apply {
                        remove(path)
                        add(0, path)
                    }
                }

                override suspend fun list(profile: ServerProfileId) = all[profile].orEmpty().toList()
            }
        }
        assertCaught("each profile keeps at most MAX_PER_PROFILE folders, dropping the oldest", unbounded)
        val oldestFirst = recentFoldersStoreContract {
            val real = FakeRecentFoldersStore()
            object : RecentFoldersStore by real {
                override suspend fun list(profile: ServerProfileId) = real.list(profile).reversed()
            }
        }
        assertCaught("add puts the newest folder first", oldestFirst)
    }

    @Test
    fun `recent folders that duplicate a folder added again are caught`() {
        val cases = recentFoldersStoreContract {
            val real = FakeRecentFoldersStore()
            object : RecentFoldersStore by real {
                override suspend fun list(profile: ServerProfileId) = real.list(profile).let { it + it.takeLast(1) }
            }
        }
        assertCaught("adding a folder again moves it to the front, once", cases)
    }

    /** Ends its event flow quietly on close, and turns Closed into a raw exception. */
    private class LooseClient(private val real: FakeOpencodeClient = FakeOpencodeClient()) : OpencodeClient by real {
        override val events: EventApi = object : EventApi by real.events {
            override fun events(types: Set<String>): Flow<ServerEvent> =
                real.events.events(types).catch { failure ->
                    if ((failure as? ApiException)?.error != ApiError.Closed) throw failure
                }
        }

        override val sessions: SessionApi = object : SessionApi by real.sessions {
            override suspend fun list(query: SessionQuery) = try {
                real.sessions.list(query)
            } catch (e: ApiException) {
                if (e.error == ApiError.Closed) throw IllegalStateException("closed") else throw e
            }
        }
    }

    @Test
    fun `a client that does not fail its collectors and running calls on close is caught`() {
        val cases = opencodeClientContract {
            val fake = FakeOpencodeClient()
            object : ApiHarness by FakeApiHarness(fake) {
                override val client: OpencodeClient = LooseClient(fake)
            }
        }
        assertCaught("an event collector fails with Closed on close, and does not reconnect", cases)
        assertCaught("every running call fails with Closed on close", cases)
        assertCaught("sessions.list after close fails with Closed", cases)
    }

    @Test
    fun `an inbox that loses queued items or mixes sessions is caught`() {
        fun casesWith(inbox: suspend (MessageApi, SessionId) -> List<InboxItem>) = messageApiContract {
            val fake = FakeOpencodeClient()
            object : ApiHarness by FakeApiHarness(fake) {
                override val client: OpencodeClient = object : OpencodeClient by fake {
                    override val messages: MessageApi = object : MessageApi by fake.messages {
                        override suspend fun inbox(sessionId: SessionId) = inbox(fake.messages, sessionId)
                    }
                }
            }
        }
        val dropsQueued = casesWith { real, id -> real.inbox(id).filter { it.delivery != PromptDelivery.Queue } }
        assertCaught("inbox lists what prompt enqueued, oldest first, as prompt returned it", dropsQueued)
        val newestFirst = casesWith { real, id -> real.inbox(id).reversed() }
        assertCaught("inbox lists what prompt enqueued, oldest first, as prompt returned it", newestFirst)
        val everySession = casesWith { real, _ -> real.inbox(SessionId("session-a")) + real.inbox(SessionId("session-b")) }
        assertCaught("inbox lists only the session's own items", everySession)
    }

    @Test
    fun `a connector that sends the credential before asking the verifier is caught`() {
        val cases = sshConnectorContract {
            val fake = FakeSshConnectorHarness()
            object : SshConnectorHarness by fake {
                private var early = 0

                override val connector: SshConnector = object : SshConnector {
                    override suspend fun connect(
                        target: SshTarget,
                        credential: SshCredential,
                        hostKeyVerifier: HostKeyVerifier,
                        options: SshConnectOptions,
                    ) = fake.connector.connect(target, credential, { t, k ->
                        early++
                        hostKeyVerifier.verify(t, k)
                    }, options)
                }

                override suspend fun credentialsReceived() = fake.credentialsReceived() + early
            }
        }
        assertCaught("a rejection fails with the presented key and sends no credential", cases)
        assertCaught("no credential is sent while the verifier is deciding", cases)
    }

    @Test
    fun `an implementation that splits output into single bytes passes the exec contract`() {
        val cases = remoteExecContract {
            val fake = FakeRemoteExecHarness()
            object : RemoteExecHarness by fake {
                override val exec: RemoteExec = object : RemoteExec by fake.exec {
                    override fun stream(request: ExecRequest): Flow<ExecEvent> =
                        fake.exec.stream(request).transform { event ->
                            when (event) {
                                is ExecEvent.Stdout -> event.bytes.forEach { emit(ExecEvent.Stdout(byteArrayOf(it))) }
                                is ExecEvent.Stderr -> event.bytes.forEach { emit(ExecEvent.Stderr(byteArrayOf(it))) }
                                is ExecEvent.Exited -> emit(event)
                            }
                        }
                }
            }
        }
        assertEquals(emptyList(), cases.filter { runCatching { it.run() }.isFailure }.map { it.name })
    }

    @Test
    fun `a contract that passes reports nothing`() {
        assertEquals(emptySet(), knownHostsStoreContract { FakeKnownHostsStore() }.failing())
    }

    private companion object {
        const val DELETE_ALL_DATA =
            "deleteAllData deletes secrets, profiles, known hosts, keys and recent folders, and resets settings"
    }
}
