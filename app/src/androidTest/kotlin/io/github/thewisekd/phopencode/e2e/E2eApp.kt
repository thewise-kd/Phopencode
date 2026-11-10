package io.github.thewisekd.phopencode.e2e

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The app as it runs: the Koin graph `PhopencodeApplication` started, its one [ConnectionManager],
 * its storage, and the feature presenters built from Koin with a route and a screen scope, exactly
 * as the navigation graphs build them. A test acts through presenter intents, as a screen does,
 * and reads what a screen would show.
 *
 * Each test gets a saved server profile of its own whose SSH key is in the app's secret store, as
 * the key import leaves it; the key import itself needs the system file picker and is not part of
 * these tests. The host key of the runner's sshd is forgotten before and after, so every connect
 * asks for its approval. [tearDown] leaves the app as it found it: disconnected, without the
 * profile, its secrets or the host key.
 */
internal class E2eApp(val env: E2eEnvironment) {
    val koin: Koin = KoinPlatform.getKoin()
    val connection: ConnectionManager = koin.get()
    private val profiles: ProfileRepository = koin.get()
    private val secrets: SecretStore = koin.get()
    private val knownHosts: KnownHostsStore = koin.get()

    private val tag = UUID.randomUUID().toString()
    val profileId = ServerProfileId("e2e-$tag")
    private val keyRef = SecretRef("e2e-key-$tag")
    val endpoint = HostEndpoint(env.target.host, env.target.port)
    private val screens = mutableListOf<Screen<*>>()

    /** A presenter with the scope of its screen; [close] is leaving the screen. */
    class Screen<P : Any>(val presenter: P, private val scope: CoroutineScope) {
        fun close() = scope.cancel()
    }

    suspend fun setUp() {
        connection.disconnect()
        knownHosts.delete(endpoint)
        val key = env.userKeyBytes()
        try {
            SecretValue.ofBytes(key).use { secrets.put(keyRef, it) }
        } finally {
            key.fill(0)
        }
        profiles.save(
            ServerProfile(
                id = profileId,
                name = "End-to-end test",
                host = env.target.host,
                port = env.target.port,
                username = env.target.username,
                auth = SshAuth.PrivateKey(keyRef),
                opencodePort = env.opencodePort,
            ),
        )
    }

    suspend fun tearDown() {
        screens.forEach { it.close() }
        screens.clear()
        connection.disconnect()
        val stored = profiles.list().firstOrNull { it.id == profileId }
        profiles.delete(profileId)
        secrets.delete(keyRef)
        stored?.opencodeCredential?.let { secrets.delete(it) }
        knownHosts.delete(endpoint)
    }

    /** The presenter Koin builds for [route], on a new screen scope, as `rememberPresenter` does. */
    inline fun <reified P : Any> open(route: Any): Screen<P> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val presenter = koin.get<P> { parametersOf(route, scope) }
        return Screen(presenter, scope).also(::track)
    }

    fun track(screen: Screen<*>) {
        screens += screen
    }

    /**
     * Connects the test's profile the way a user does, and checks every step on the way:
     *
     * 1. The progress screen connects; SSH reaches the server, whose key is new, so the progress
     *    screen opens the host key screen.
     * 2. The host key screen shows the runner's ed25519 key with its exact fingerprint; approving it
     *    lets the connect go on.
     * 3. Server detection finds the running `opencode serve`, which this app did not start, so its
     *    password is unknown and the progress screen asks for it.
     * 4. The password the user enters is stored as the profile's credential and the connect starts
     *    again: the key is known now, the server is found, the API and its version check pass.
     *
     * Returns the standing connection.
     */
    suspend fun connect(): ConnectionState.Connected {
        val progress = open<ProgressPresenter>(ConnectRoute.Progress(profileId.value))
        assertEquals(ProgressEvent.OpenHostKey, await(progress.presenter.events, "the host key screen"))

        val hostKey = open<HostKeyPresenter>(ConnectRoute.HostKey(profileId.value))
        val shown = await(hostKey.presenter.state, "the host key") { it.request != null }
        val request = shown.request as? HostKeyRequest.FirstSeen ?: throw AssertionError("Not a first-seen key: $shown")
        assertEquals("ssh-ed25519", request.keyType)
        assertEquals(env.hostKeyFingerprint, request.fingerprint.sha256)
        hostKey.presenter.onIntent(HostKeyIntent.Approve)
        assertEquals(HostKeyEvent.Close, await(hostKey.presenter.events, "the host key decision"))
        hostKey.close()
        assertEquals(env.hostKeyFingerprint, knownHosts.approved(endpoint)?.sha256)

        val asking = await(progress.presenter.state, "the password question") { it.error != null }
        assertEquals(asking.toString(), ProgressError.ServerPasswordUnknown, asking.error)
        assertEquals(ProgressStep.Server, asking.step)
        assertTrue(asking.toString(), asking.askingServerPassword)
        progress.presenter.onIntent(ProgressIntent.SubmitServerPassword(env.readOpencodePassword()))

        assertEquals(ProgressEvent.Connected, await(progress.presenter.events, "the connection"))
        assertEquals(ProgressStep.Connected, progress.presenter.state.value.step)
        // Leaving the progress screen keeps a connection that stands, for the screens after it.
        progress.close()
        val state = connection.state.value
        return state as? ConnectionState.Connected ?: throw AssertionError("Not connected: $state")
    }

    /** Uploads [content] as [name] into the project folder through the transfer screen. */
    suspend fun upload(name: String, content: ByteArray): TransferJob {
        val transfer = open<TransferPresenter>(FilesRoute.Transfer(env.projectDir, env.projectDir))
        transfer.presenter.onIntent(TransferIntent.Upload(listOf(MemoryDocument(name, content))))
        val finished = await(transfer.presenter.state, "the upload") { state ->
            state.jobs.any { it.localName == name && it.status.isFinished }
        }
        transfer.close()
        return finished.jobs.single { it.localName == name }
    }

    companion object {
        /** An emulator is slow, API 26 most of all, and a connect walks several round trips. */
        val STEP: Duration = 2.minutes

        suspend fun <E> await(events: Flow<E>, what: String): E = try {
            withTimeout(STEP) { events.first() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("No event for $what within $STEP", timeout)
        }

        /** Waits for a state of [flow] that meets [done]; a timeout names the last state, masked. */
        suspend fun <S> await(flow: StateFlow<S>, what: String, done: (S) -> Boolean): S = try {
            withTimeout(STEP) { flow.first(done) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Waited $STEP for $what; the screen shows ${flow.value}", timeout)
        }
    }
}

/** A local document of the system picker, held in memory. */
internal class MemoryDocument(override val name: String, private val content: ByteArray) : LocalDocument {
    override val sizeBytes: Long get() = content.size.toLong()

    override fun openInput(): InputStream = ByteArrayInputStream(content)

    override fun openOutput(): OutputStream = throw UnsupportedOperationException("Upload only")

    override fun delete(): Boolean = false
}
