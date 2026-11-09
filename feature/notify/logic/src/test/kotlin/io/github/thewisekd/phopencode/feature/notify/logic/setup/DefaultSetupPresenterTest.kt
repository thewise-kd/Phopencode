package io.github.thewisekd.phopencode.feature.notify.logic.setup

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifySecrets
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServerProblem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyTopic
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DefaultSetupPresenter] keeps one ntfy topic of at least 128 random bits and one ntfy server in
 * the secret store, runs the notification service through [NotifyService] one step at a time, and
 * never lets the topic out except to the store, the service and the masked subscription link.
 *
 * Every topic and server here is a made-up test value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSetupPresenterTest {
    /** A made-up topic that is valid: 22 characters of the alphabet. */
    private val storedTopic = "TestTopicAbcdefghij_-0"

    /** A made-up server of the user's own. */
    private val ownServer = "https://ntfy.example.test"

    /** A secret store that records every value it hands out or takes, to prove they are closed. */
    private class TrackingSecrets(val store: FakeSecretStore = FakeSecretStore()) : SecretStore {
        val handedOut = mutableListOf<SecretValue>()
        val taken = mutableListOf<SecretValue>()
        val puts = mutableListOf<SecretRef>()
        var readFailure: Exception? = null
        var putFailure: Exception? = null
        var putGate: CompletableDeferred<Unit>? = null

        override suspend fun put(ref: SecretRef, value: SecretValue) {
            puts += ref
            taken += value
            putGate?.await()
            putFailure?.let { throw it }
            store.put(ref, value)
        }

        override suspend fun read(ref: SecretRef): SecretReadResult {
            readFailure?.let { throw it }
            val result = store.read(ref)
            if (result is SecretReadResult.Found) handedOut += result.value
            return result
        }

        override suspend fun delete(ref: SecretRef) = store.delete(ref)

        override suspend fun deleteAll() = store.deleteAll()

        suspend fun text(ref: SecretRef): String? =
            (store.read(ref) as? SecretReadResult.Found)?.value?.use { it.revealText() }

        suspend fun seed(ref: SecretRef, text: String) = SecretValue.ofText(text).use { store.put(ref, it) }

        suspend fun seedBytes(ref: SecretRef, bytes: ByteArray) = SecretValue.ofBytes(bytes).use { store.put(ref, it) }
    }

    private class FakeService : NotifyService {
        val calls = mutableListOf<String>()
        val settings = mutableListOf<NtfySettings>()
        var current = NotifyServiceStatus.NotInstalled
        val failures = mutableMapOf<String, Throwable>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var cancelledIn: String? = null

        private suspend fun step(name: String) {
            calls += name
            try {
                gates[name]?.await()
            } catch (e: CancellationException) {
                cancelledIn = name
                throw e
            }
            failures[name]?.let { throw it }
        }

        override suspend fun status(): NotifyServiceStatus {
            step("status")
            return current
        }

        override suspend fun install(settings: NtfySettings) {
            this.settings += settings
            step("install")
            current = NotifyServiceStatus.Running
        }

        override suspend fun remove() {
            step("remove")
            current = NotifyServiceStatus.NotInstalled
        }

        override suspend fun sendTest(settings: NtfySettings) {
            this.settings += settings
            step("sendTest")
        }
    }

    /** Hands out [bytes] in a cycle; records how many were asked for. */
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        var drawn = 0

        override fun nextBytes(out: ByteArray) {
            for (i in out.indices) out[i] = bytes[(drawn + i) % bytes.size]
            drawn += out.size
        }
    }

    private val secrets = TrackingSecrets()
    private val service = FakeService()
    private val random = FixedRandom(ByteArray(64) { it.toByte() })

    /** The topic [random] makes: byte i gives the i-th character of the alphabet. */
    private val madeTopic = NtfyTopic.ALPHABET.take(NtfyTopic.MIN_LENGTH)

    private fun TestScope.presenter(scope: CoroutineScope = backgroundScope): DefaultSetupPresenter =
        DefaultSetupPresenter(secrets = secrets, service = service, random = random, scope = scope)

    /** A loaded presenter, with every state and event it shows recorded. */
    private class Recorded(val presenter: DefaultSetupPresenter) {
        val states = mutableListOf<SetupState>()
        val events = mutableListOf<SetupEvent>()

        val state: SetupState
            get() = presenter.state.value
    }

    private fun TestScope.loaded(scope: CoroutineScope = backgroundScope): Recorded {
        val recorded = Recorded(presenter(scope))
        backgroundScope.launch { recorded.presenter.state.collect { recorded.states += it } }
        backgroundScope.launch { recorded.presenter.events.collect { recorded.events += it } }
        runCurrent()
        return recorded
    }

    // Loading

    @Test
    fun `nothing runs before the scope does`() = runTest {
        val presenter = presenter()

        assertEquals(SetupState(), presenter.state.value)
        assertTrue(service.calls.isEmpty())
        assertTrue(secrets.puts.isEmpty())
        assertEquals(0, random.drawn)
    }

    @Test
    fun `a first load makes a topic, stores it, and reads the status`() = runTest {
        service.current = NotifyServiceStatus.Stopped
        service.gates["status"] = CompletableDeferred()
        val r = loaded()
        // The status is read as its own step, after loading.
        assertFalse(r.state.loading)
        assertEquals(SetupStep.CheckStatus, r.state.operation)
        service.gates.getValue("status").complete(Unit)
        runCurrent()

        assertEquals(madeTopic, secrets.text(NotifySecrets.TOPIC))
        assertEquals(listOf(NotifySecrets.TOPIC), secrets.puts)
        assertEquals(listOf("status"), service.calls)
        assertEquals(
            SetupState(
                loading = false,
                server = NtfyServer.DEFAULT,
                serverText = NtfyServer.DEFAULT.url,
                subscriptionLink = "ntfy://ntfy.sh/$madeTopic",
                status = NotifyServiceStatus.Stopped,
            ),
            r.state,
        )
    }

    @Test
    fun `a new topic has at least 128 bits drawn uniformly from the alphabet`() = runTest {
        // Every byte value maps to a character the same number of times: byte b gives ALPHABET[b % 64].
        val bytes = ByteArray(NtfyTopic.MIN_LENGTH) { (it * 37 + 200).toByte() }
        val fixed = FixedRandom(bytes)
        val presenter = DefaultSetupPresenter(secrets, service, fixed, backgroundScope)
        runCurrent()

        val topic = assertNotNull(secrets.text(NotifySecrets.TOPIC))
        val expected = bytes.map { NtfyTopic.ALPHABET[(it.toInt() and 0xFF) % 64] }.joinToString("")
        assertEquals(expected, topic)
        assertTrue(topic.length * NtfyTopic.BITS_PER_CHARACTER >= 128)
        assertNotNull(NtfyTopic.of(topic))
        assertTrue(fixed.drawn >= NtfyTopic.MIN_LENGTH)
        assertNotNull(presenter.state.value.subscriptionLink)
    }

    @Test
    fun `the system random source makes a different valid topic each time`() = runTest {
        val first = TrackingSecrets()
        val second = TrackingSecrets()
        DefaultSetupPresenter(first, service, SecureRandom(), backgroundScope)
        DefaultSetupPresenter(second, service, SecureRandom(), backgroundScope)
        runCurrent()

        val a = assertNotNull(first.text(NotifySecrets.TOPIC))
        val b = assertNotNull(second.text(NotifySecrets.TOPIC))
        assertNotNull(NtfyTopic.of(a))
        assertNotNull(NtfyTopic.of(b))
        assertNotEquals(a, b)
    }

    @Test
    fun `a stored topic and server are used as they are`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, storedTopic)
        secrets.seed(NotifySecrets.SERVER, "$ownServer/")
        val r = loaded()

        assertTrue(secrets.puts.isEmpty())
        assertEquals(0, random.drawn)
        assertEquals(NtfyServer.parse(ownServer), r.state.server)
        assertEquals(ownServer, r.state.serverText)
        assertEquals("ntfy://ntfy.example.test/$storedTopic", r.state.subscriptionLink)
        assertFalse(r.state.loading)
    }

    @Test
    fun `an unreadable topic is replaced by a new one`() = runTest {
        secrets.store.seedUnreadable(NotifySecrets.TOPIC)
        val r = loaded()

        assertEquals(madeTopic, secrets.text(NotifySecrets.TOPIC))
        assertEquals("ntfy://ntfy.sh/$madeTopic", r.state.subscriptionLink)
        assertNull(r.state.error)
    }

    @Test
    fun `a stored topic that is too weak is replaced by a new one`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, "short")
        loaded()

        assertEquals(madeTopic, secrets.text(NotifySecrets.TOPIC))
    }

    @Test
    fun `a stored topic that is not UTF-8 is replaced by a new one`() = runTest {
        secrets.seedBytes(NotifySecrets.TOPIC, ByteArray(30) { 0xC3.toByte() })
        loaded()

        assertEquals(madeTopic, secrets.text(NotifySecrets.TOPIC))
    }

    @Test
    fun `an unreadable or invalid server is the default one and is not written`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, storedTopic)
        secrets.seed(NotifySecrets.SERVER, "http://ntfy.example.test")
        val r = loaded()

        assertEquals(NtfyServer.DEFAULT, r.state.server)
        assertTrue(secrets.puts.isEmpty())

        val other = TrackingSecrets()
        other.seed(NotifySecrets.TOPIC, storedTopic)
        other.store.seedUnreadable(NotifySecrets.SERVER)
        val presenter = DefaultSetupPresenter(other, service, random, backgroundScope)
        runCurrent()
        assertEquals(NtfyServer.DEFAULT, presenter.state.value.server)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `every secret value read or written is closed once used`() = runTest {
        secrets.seed(NotifySecrets.SERVER, ownServer)
        secrets.seed(NotifySecrets.TOPIC, "short")
        val r = loaded()
        r.presenter.onIntent(SetupIntent.EditServer("https://other.example.test"))
        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()

        assertEquals(2, secrets.handedOut.size)
        assertEquals(2, secrets.taken.size)
        (secrets.handedOut + secrets.taken).forEach { value ->
            assertFailsWith<IllegalStateException> { value.revealBytes() }
        }
    }

    @Test
    fun `a store that cannot be read fails the load, and refreshing loads again`() = runTest {
        secrets.readFailure = IOException("unavailable")
        val r = loaded()

        assertFalse(r.state.loading)
        assertNull(r.state.operation)
        assertNull(r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.Load, NotifyFailure.StorageFailed), r.state.error)
        assertTrue(service.calls.isEmpty())

        // Nothing that needs the topic runs without it.
        r.presenter.onIntent(SetupIntent.Install)
        r.presenter.onIntent(SetupIntent.SendTest)
        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()
        assertTrue(service.calls.isEmpty())
        assertTrue(secrets.puts.isEmpty())

        secrets.readFailure = null
        r.presenter.onIntent(SetupIntent.RefreshStatus)
        runCurrent()
        assertEquals("ntfy://ntfy.sh/$madeTopic", r.state.subscriptionLink)
        assertNull(r.state.error)
        assertEquals(listOf("status"), service.calls)
    }

    @Test
    fun `a new topic that could not be stored is never used`() = runTest {
        secrets.putFailure = IOException("unavailable")
        val r = loaded()

        assertNull(r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.Load, NotifyFailure.StorageFailed), r.state.error)
        r.presenter.onIntent(SetupIntent.SendTest)
        runCurrent()
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `a status that cannot be read is shown as unknown with the reason`() = runTest {
        service.failures["status"] = NotifyException(NotifyFailure.NotConnected)
        val r = loaded()

        assertFalse(r.state.loading)
        assertNull(r.state.status)
        assertNull(r.state.operation)
        assertNotNull(r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.CheckStatus, NotifyFailure.NotConnected), r.state.error)

        service.failures.clear()
        r.presenter.onIntent(SetupIntent.RefreshStatus)
        runCurrent()
        assertEquals(NotifyServiceStatus.NotInstalled, r.state.status)
        assertNull(r.state.error)
    }

    // The service

    @Test
    fun `install runs with the stored server and topic, then reads the status again`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, storedTopic)
        secrets.seed(NotifySecrets.SERVER, ownServer)
        val r = loaded()
        service.gates["install"] = CompletableDeferred()

        r.presenter.onIntent(SetupIntent.Install)
        assertEquals(SetupStep.Install, r.state.operation)
        assertTrue(r.state.busy)
        runCurrent()
        assertEquals(SetupStep.Install, r.state.operation)

        service.gates["status"] = CompletableDeferred()
        service.gates.getValue("install").complete(Unit)
        runCurrent()
        assertEquals(SetupStep.CheckStatus, r.state.operation)
        service.gates.getValue("status").complete(Unit)
        runCurrent()

        assertEquals(listOf("status", "install", "status"), service.calls)
        val settings = service.settings.single()
        assertEquals(NtfyServer.parse(ownServer), settings.server)
        assertEquals(storedTopic, settings.topic.reveal())
        assertEquals(NotifyServiceStatus.Running, r.state.status)
        assertNull(r.state.operation)
        assertNull(r.state.error)
    }

    @Test
    fun `a failed install keeps its reason and still reads the status again`() = runTest {
        val r = loaded()
        service.failures["install"] = NotifyException(NotifyFailure.PythonMissing)

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()

        assertEquals(listOf("status", "install", "status"), service.calls)
        assertEquals(SetupError(SetupStep.Install, NotifyFailure.PythonMissing), r.state.error)
        assertEquals(NotifyServiceStatus.NotInstalled, r.state.status)
        assertNull(r.state.operation)
    }

    @Test
    fun `when both the install and the status read fail, the install's reason is shown`() = runTest {
        val r = loaded()
        service.failures["install"] = NotifyException(NotifyFailure.InstallFailed)
        service.failures["status"] = NotifyException(NotifyFailure.Unreachable)

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()

        assertEquals(SetupError(SetupStep.Install, NotifyFailure.InstallFailed), r.state.error)
        assertNull(r.state.status)
    }

    @Test
    fun `a status read failing after a good install is shown`() = runTest {
        val r = loaded()
        service.failures["status"] = NotifyException(NotifyFailure.Unreachable)

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()

        assertEquals(SetupError(SetupStep.CheckStatus, NotifyFailure.Unreachable), r.state.error)
        assertNull(r.state.status)
    }

    @Test
    fun `remove removes the service, then reads the status again`() = runTest {
        service.current = NotifyServiceStatus.Running
        val r = loaded()
        assertEquals(NotifyServiceStatus.Running, r.state.status)

        r.presenter.onIntent(SetupIntent.Remove)
        assertEquals(SetupStep.Remove, r.state.operation)
        runCurrent()

        assertEquals(listOf("status", "remove", "status"), service.calls)
        assertEquals(NotifyServiceStatus.NotInstalled, r.state.status)
        assertNull(r.state.operation)

        service.failures["remove"] = NotifyException(NotifyFailure.InstallFailed)
        r.presenter.onIntent(SetupIntent.Remove)
        runCurrent()
        assertEquals(SetupError(SetupStep.Remove, NotifyFailure.InstallFailed), r.state.error)
    }

    @Test
    fun `a test notification goes through the service with the stored settings`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, storedTopic)
        val r = loaded()

        r.presenter.onIntent(SetupIntent.SendTest)
        assertEquals(SetupStep.SendTest, r.state.operation)
        runCurrent()

        assertEquals(listOf("status", "sendTest"), service.calls)
        assertEquals(storedTopic, service.settings.single().topic.reveal())
        assertEquals(NtfyServer.DEFAULT, service.settings.single().server)
        assertTrue(r.state.testSent)
        assertNull(r.state.operation)

        // The next step clears it.
        r.presenter.onIntent(SetupIntent.RefreshStatus)
        assertFalse(r.state.testSent)
        runCurrent()
        assertFalse(r.state.testSent)
    }

    @Test
    fun `a failed test notification shows why`() = runTest {
        val r = loaded()
        service.failures["sendTest"] = NotifyException(NotifyFailure.NtfyFailed)

        r.presenter.onIntent(SetupIntent.SendTest)
        runCurrent()

        assertFalse(r.state.testSent)
        assertEquals(SetupError(SetupStep.SendTest, NotifyFailure.NtfyFailed), r.state.error)

        // Starting another step clears the old error.
        service.failures.clear()
        service.gates["sendTest"] = CompletableDeferred()
        r.presenter.onIntent(SetupIntent.SendTest)
        assertNull(r.state.error)
    }

    @Test
    fun `an unexpected exception from the service is an unknown failure`() = runTest {
        val r = loaded()
        service.failures["sendTest"] = IllegalStateException("boom")

        r.presenter.onIntent(SetupIntent.SendTest)
        runCurrent()

        assertEquals(SetupError(SetupStep.SendTest, NotifyFailure.Unknown), r.state.error)
        assertNull(r.state.operation)
    }

    @Test
    fun `an IOException from the service is an unknown failure, not a storage one`() = runTest {
        service.failures["status"] = IOException("socket")
        val r = loaded()
        assertEquals(SetupError(SetupStep.CheckStatus, NotifyFailure.Unknown), r.state.error)

        service.failures.clear()
        service.failures["install"] = IOException("socket")
        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()
        assertEquals(SetupError(SetupStep.Install, NotifyFailure.Unknown), r.state.error)

        service.failures.clear()
        service.failures["remove"] = IOException("socket")
        r.presenter.onIntent(SetupIntent.Remove)
        runCurrent()
        assertEquals(SetupError(SetupStep.Remove, NotifyFailure.Unknown), r.state.error)

        service.failures.clear()
        service.failures["sendTest"] = IOException("socket")
        r.presenter.onIntent(SetupIntent.SendTest)
        runCurrent()
        assertEquals(SetupError(SetupStep.SendTest, NotifyFailure.Unknown), r.state.error)
    }

    @Test
    fun `an unexpected exception from the store while loading is an unknown failure`() = runTest {
        secrets.readFailure = IllegalStateException("boom")
        val r = loaded()

        assertFalse(r.state.loading)
        assertNull(r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.Load, NotifyFailure.Unknown), r.state.error)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `an unexpected exception from the store while storing a new topic is an unknown failure`() = runTest {
        secrets.putFailure = IllegalStateException("boom")
        val r = loaded()

        assertNull(r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.Load, NotifyFailure.Unknown), r.state.error)
    }

    @Test
    fun `an unexpected exception from the store while saving the server is an unknown failure`() = runTest {
        val r = loaded()
        secrets.putFailure = IllegalStateException("boom")

        r.presenter.onIntent(SetupIntent.EditServer(ownServer))
        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()

        assertEquals(NtfyServer.DEFAULT, r.state.server)
        assertEquals(SetupError(SetupStep.SaveServer, NotifyFailure.Unknown), r.state.error)
    }

    @Test
    fun `a cancellation from inside the store while loading, not the scope's, is a failure`() = runTest {
        secrets.readFailure = CancellationException("timed out")
        val r = loaded()

        assertFalse(r.state.loading)
        assertEquals(SetupError(SetupStep.Load, NotifyFailure.Unknown), r.state.error)
    }

    @Test
    fun `a cancellation from inside the service, not the scope's, is a failure`() = runTest {
        val r = loaded()
        service.failures["install"] = CancellationException("timed out")

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()

        assertEquals(SetupError(SetupStep.Install, NotifyFailure.Unknown), r.state.error)
        assertNull(r.state.operation)
    }

    @Test
    fun `cancelling the scope cancels a running step and records no failure`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val r = loaded(scope)
        service.gates["install"] = CompletableDeferred()

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()
        scope.cancel()
        runCurrent()

        assertEquals("install", service.cancelledIn)
        assertNull(r.state.error)
        assertEquals(listOf("status", "install"), service.calls)
    }

    @Test
    fun `cancelling the scope while loading stores nothing more`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        secrets.putGate = CompletableDeferred()
        val r = loaded(scope)

        scope.cancel()
        runCurrent()

        assertTrue(service.calls.isEmpty())
        assertNull(r.state.error)
        assertTrue(r.state.loading)
    }

    @Test
    fun `nothing starts while loading or while another step runs`() = runTest {
        secrets.putGate = CompletableDeferred()
        val r = loaded()
        r.presenter.onIntent(SetupIntent.Install)
        r.presenter.onIntent(SetupIntent.Remove)
        r.presenter.onIntent(SetupIntent.SendTest)
        r.presenter.onIntent(SetupIntent.RefreshStatus)
        r.presenter.onIntent(SetupIntent.ResetServer)
        secrets.putGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("status"), service.calls)
        assertEquals(listOf(NotifySecrets.TOPIC), secrets.puts)

        service.gates["install"] = CompletableDeferred()
        r.presenter.onIntent(SetupIntent.Install)
        r.presenter.onIntent(SetupIntent.Install)
        r.presenter.onIntent(SetupIntent.Remove)
        r.presenter.onIntent(SetupIntent.SendTest)
        r.presenter.onIntent(SetupIntent.RefreshStatus)
        r.presenter.onIntent(SetupIntent.ResetServer)
        runCurrent()
        service.gates.getValue("install").complete(Unit)
        runCurrent()

        assertEquals(listOf("status", "install", "status"), service.calls)
        assertEquals(listOf(NotifySecrets.TOPIC), secrets.puts)
    }

    // The server

    @Test
    fun `editing the server shows why it cannot be saved`() = runTest {
        val r = loaded()

        r.presenter.onIntent(SetupIntent.EditServer(" "))
        assertEquals(NtfyServerProblem.Blank, r.state.serverProblem)
        r.presenter.onIntent(SetupIntent.EditServer("http://ntfy.example.test"))
        assertEquals(NtfyServerProblem.NotHttps, r.state.serverProblem)
        r.presenter.onIntent(SetupIntent.EditServer("https://ntfy.example.test/path"))
        assertEquals(NtfyServerProblem.Invalid, r.state.serverProblem)
        assertEquals("https://ntfy.example.test/path", r.state.serverText)

        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()
        assertTrue(secrets.puts.none { it == NotifySecrets.SERVER })
        assertEquals(NtfyServer.DEFAULT, r.state.server)

        r.presenter.onIntent(SetupIntent.EditServer("$ownServer:443/"))
        assertNull(r.state.serverProblem)
        assertTrue(r.state.serverEdited)
    }

    @Test
    fun `saving a valid server stores it and updates the link`() = runTest {
        val r = loaded()

        r.presenter.onIntent(SetupIntent.EditServer("  HTTPS://NTFY.Example.Test:8443/ "))
        r.presenter.onIntent(SetupIntent.SaveServer)
        assertEquals(SetupStep.SaveServer, r.state.operation)
        runCurrent()

        assertEquals("https://ntfy.example.test:8443", secrets.text(NotifySecrets.SERVER))
        assertEquals("https://ntfy.example.test:8443", r.state.server.url)
        assertEquals("https://ntfy.example.test:8443", r.state.serverText)
        assertFalse(r.state.serverEdited)
        assertEquals("ntfy://ntfy.example.test:8443/$madeTopic", r.state.subscriptionLink)
        assertNull(r.state.operation)
        assertNull(r.state.error)
        // Saving does not touch the service: an installed one keeps the old server until reinstalled.
        assertEquals(listOf("status"), service.calls)

        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()
        assertEquals("https://ntfy.example.test:8443", service.settings.single().server.url)
    }

    @Test
    fun `a server typed while saving is kept`() = runTest {
        val r = loaded()
        secrets.putGate = CompletableDeferred()

        r.presenter.onIntent(SetupIntent.EditServer(ownServer))
        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()
        r.presenter.onIntent(SetupIntent.EditServer("https://typed.example.test"))
        secrets.putGate!!.complete(Unit)
        runCurrent()

        assertEquals(ownServer, r.state.server.url)
        assertEquals("https://typed.example.test", r.state.serverText)
    }

    @Test
    fun `a server that cannot be stored keeps the old one`() = runTest {
        val r = loaded()
        secrets.putFailure = IOException("unavailable")

        r.presenter.onIntent(SetupIntent.EditServer(ownServer))
        r.presenter.onIntent(SetupIntent.SaveServer)
        runCurrent()

        assertEquals(NtfyServer.DEFAULT, r.state.server)
        assertEquals(ownServer, r.state.serverText)
        assertEquals("ntfy://ntfy.sh/$madeTopic", r.state.subscriptionLink)
        assertEquals(SetupError(SetupStep.SaveServer, NotifyFailure.StorageFailed), r.state.error)
        assertNull(r.state.operation)
    }

    @Test
    fun `resetting the server stores the default one`() = runTest {
        secrets.seed(NotifySecrets.SERVER, ownServer)
        val r = loaded()
        r.presenter.onIntent(SetupIntent.EditServer("https://typed.example.test"))

        r.presenter.onIntent(SetupIntent.ResetServer)
        runCurrent()

        assertEquals(NtfyServer.DEFAULT.url, secrets.text(NotifySecrets.SERVER))
        assertEquals(NtfyServer.DEFAULT, r.state.server)
        assertEquals(NtfyServer.DEFAULT.url, r.state.serverText)
        assertNull(r.state.serverProblem)
        assertEquals("ntfy://ntfy.sh/$madeTopic", r.state.subscriptionLink)
    }

    @Test
    fun `a server typed while resetting is kept`() = runTest {
        secrets.seed(NotifySecrets.SERVER, ownServer)
        val r = loaded()
        r.presenter.onIntent(SetupIntent.EditServer("https://before.example.test"))
        secrets.putGate = CompletableDeferred()

        r.presenter.onIntent(SetupIntent.ResetServer)
        runCurrent()
        r.presenter.onIntent(SetupIntent.EditServer("https://typed.example.test"))
        secrets.putGate!!.complete(Unit)
        runCurrent()

        assertEquals(NtfyServer.DEFAULT.url, secrets.text(NotifySecrets.SERVER))
        assertEquals(NtfyServer.DEFAULT, r.state.server)
        assertEquals("https://typed.example.test", r.state.serverText)
        assertTrue(r.state.serverEdited)
    }

    @Test
    fun `an edit from another thread racing a save is never overwritten by it`() = runTest {
        val first = "https://first.example.test"
        val second = "https://second.example.test"
        repeat(RACE_ROUNDS) { round ->
            val presenter = DefaultSetupPresenter(TrackingSecrets(), FakeService(), random, backgroundScope)
            runCurrent()
            presenter.onIntent(SetupIntent.EditServer(first))
            val barrier = CyclicBarrier(2)
            // Sweeps the edit across the save's start, a little later each round.
            val editor = thread {
                barrier.await()
                repeat(round % RACE_SPREAD) { Thread.onSpinWait() }
                presenter.onIntent(SetupIntent.EditServer(second))
            }
            barrier.await()
            presenter.onIntent(SetupIntent.SaveServer)
            editor.join()
            runCurrent()

            // Whichever text was saved, the last one typed is what the field shows.
            assertEquals(second, presenter.state.value.serverText, "round $round")
            assertNull(presenter.state.value.operation)
        }
    }

    @Test
    fun `the server field follows the stored server until the user types`() = runTest {
        secrets.seed(NotifySecrets.SERVER, ownServer)
        secrets.putGate = CompletableDeferred()
        val r = loaded()
        r.presenter.onIntent(SetupIntent.EditServer("https://typed.example.test"))
        secrets.putGate!!.complete(Unit)
        runCurrent()

        assertEquals(ownServer, r.state.server.url)
        assertEquals("https://typed.example.test", r.state.serverText)
    }

    // The rest

    @Test
    fun `dismissing clears the error and close is sent once`() = runTest {
        service.failures["status"] = NotifyException(NotifyFailure.Unreachable)
        val r = loaded()
        assertNotNull(r.state.error)

        r.presenter.onIntent(SetupIntent.DismissError)
        r.presenter.onIntent(SetupIntent.Close)
        runCurrent()

        assertNull(r.state.error)
        assertEquals(listOf<SetupEvent>(SetupEvent.Close), r.events)
    }

    @Test
    fun `the topic and the link never show in printed state, events, settings or failures`() = runTest {
        secrets.seed(NotifySecrets.TOPIC, storedTopic)
        secrets.seed(NotifySecrets.SERVER, ownServer)
        val r = loaded()
        service.failures["sendTest"] = NotifyException(NotifyFailure.NtfyFailed)
        r.presenter.onIntent(SetupIntent.SendTest)
        runCurrent()
        service.failures.clear()
        r.presenter.onIntent(SetupIntent.Install)
        runCurrent()
        r.presenter.onIntent(SetupIntent.EditServer("https://typed.example.test"))
        r.presenter.onIntent(SetupIntent.Close)
        runCurrent()

        val printed = buildList {
            addAll(r.states.map { it.toString() })
            addAll(r.events.map { it.toString() })
            addAll(service.settings.map { it.toString() })
            addAll(service.settings.map { it.topic.toString() })
            add(r.presenter.toString())
            add(NotifyException(NotifyFailure.NtfyFailed).message.orEmpty())
        }
        assertTrue(r.states.any { it.subscriptionLink != null })
        for (text in printed) {
            assertFalse(storedTopic in text, "the topic leaked: a printed value holds it")
            assertFalse("ntfy.example.test" in text, "the server leaked: a printed value holds it")
            assertFalse("typed.example.test" in text, "the typed server leaked: a printed value holds it")
        }
    }

    private companion object {
        /** Rounds of the thread races; each moves the racing edit a little later. */
        const val RACE_ROUNDS = 4000

        /** The most spins the racing edit waits before it runs. */
        const val RACE_SPREAD = 400
    }
}
