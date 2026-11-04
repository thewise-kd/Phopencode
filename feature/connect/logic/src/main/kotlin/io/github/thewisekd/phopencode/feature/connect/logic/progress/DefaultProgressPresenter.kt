package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Connects to a saved server (P4.14) through [ConnectionManager], which finds the running
 * `opencode serve` or, only when none runs at all, starts one. This presenter adds no other path to
 * the server: it never stops, kills or restarts a server process, and leaves every decision about
 * the server to the manager.
 *
 * Connecting: once [scope] runs, the profile is read and connected, and [ActiveServer] is set to
 * [serverId] just before, so it already holds this server when the connection stands. The manager's
 * steps become [ProgressState.step]; a new host key sends [ProgressEvent.OpenHostKey] once per
 * approval asked for; a connection that stands sends [ProgressEvent.Connected] once. A failure
 * becomes a [ProgressError] with the [ProgressAction]s that can help (see [progressFailure]).
 *
 * A foreign server: when the password of a running server is unknown or refused, the state asks for
 * it. [ProgressIntent.SubmitServerPassword] turns the text into a [SecretValue] at once, stores it
 * under a fresh [SecretRef], points the profile's `opencodeCredential` at it and connects again. The
 * credential it replaces is kept, since this presenter cannot tell whether anything else still refers
 * to it (see [storePassword]). The password is never in the state; the [SecretValue] is wiped as soon
 * as the store has it or the write failed. The intent's `String` cannot be wiped and stays in memory
 * until it is collected.
 *
 * Whose connect: the manager is one per app, and several progress screens may live for a moment,
 * for example while one leaves and the next comes in. Each connect records this presenter as the
 * manager's owner; once another progress presenter connects, this one follows the manager no more,
 * and neither its scope ending nor its Cancel touches the newer connect. Every connect, and every
 * owner check together with the disconnect it decides on, runs under one process-wide lock, so no
 * newer connect can fall between the check and the disconnect.
 *
 * When [scope] ends, also before the presenter got to start or before a Cancel's disconnect ran:
 * - A connection that stood, or stands right now, is kept: the next screens use it.
 * - An unfinished connect of this presenter (SSH, host approval, server or API step) is
 *   disconnected, so that no attempt goes on, or waits for a host key decision, with no screen to
 *   show it. Leaving the screen is a cancel.
 * - A connect that failed has nothing open, and nothing is done.
 * - No connect of this presenter starts afterwards, even one already past its cancellation.
 * - After a Cancel, this presenter's connect is disconnected even if it stood, exactly once,
 *   whichever of the scope's cleanup and the Cancel's runs first.
 *
 * Events: [ProgressEvent.OpenHostKey] and [ProgressEvent.Connected] are sent only while this
 * presenter owns the manager's connect, checked under the same lock as every change of owner.
 *
 * @param serverId The server to connect to.
 * @param connection Connects, and tells each step and error through its state.
 * @param profiles Gives the profile to connect with, and saves the `opencode serve` credential.
 * @param secrets Stores the `opencode serve` password the user enters for a foreign server.
 * @param activeServer Set to [serverId] before connecting, for the folder picker.
 * @param newId Makes the ID of the credential's `SecretRef`.
 */
class DefaultProgressPresenter(
    private val serverId: ServerProfileId,
    private val connection: ConnectionManager,
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val activeServer: ActiveServer,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProgressPresenter {
    private val mutableState = MutableStateFlow(ProgressState())

    override val state: StateFlow<ProgressState> = mutableState.asStateFlow()

    /** Unlimited, so that a send under the owners lock never fails or waits. */
    private val eventChannel = Channel<ProgressEvent>(Channel.UNLIMITED)

    override val events: Flow<ProgressEvent> = eventChannel.receiveAsFlow()

    /** Stands for this presenter in [owners]; holds no reference back to it. */
    private val token = Any()

    /** Guarded by itself: the attempt running now, so that a new one replaces it. */
    private val attemptLock = Any()
    private var attempt: Job? = null

    /** A connection of this presenter stood; it is kept when [scope] ends. */
    @Volatile
    private var stood = false

    /** Cancel was asked for: nothing more is shown or sent but the close. */
    @Volatile
    private var closing = false

    private val connectedSent = AtomicBoolean(false)

    /** Guarded by [connectLock]: [scope] ended and [leave] ran; nothing connects any more. */
    private var left = false

    init {
        // Atomic, so that the cleanup in finally runs even when the scope ends before this starts.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                // On an immediate dispatcher this would run inside the constructor; let it return.
                yield()
                runAttempt { loadProfile() }
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { leave() }
            }
        }
    }

    override fun onIntent(intent: ProgressIntent) {
        if (closing) return
        when (intent) {
            ProgressIntent.Retry -> retry()
            ProgressIntent.Cancel -> cancel()
            ProgressIntent.EditServer -> eventChannel.trySend(ProgressEvent.OpenServerForm)
            ProgressIntent.ReviewHostKey -> eventChannel.trySend(ProgressEvent.OpenHostKey)
            ProgressIntent.EnterServerPassword -> mutableState.update {
                if (ProgressAction.EnterServerPassword in it.actions) it.copy(askingServerPassword = true) else it
            }
            ProgressIntent.DismissServerPassword -> mutableState.update { it.copy(askingServerPassword = false) }
            is ProgressIntent.SubmitServerPassword -> submit(intent.password)
        }
    }

    /** Starts over after an error; does nothing while a connect runs. */
    private fun retry() {
        if (!restart { it.error != null }) return
        launchAttempt { loadProfile() }
    }

    private fun submit(password: String) {
        // The text itself is a String and cannot be wiped; the bytes made from it can.
        if (password.isEmpty()) return
        if (!restart { it.askingServerPassword }) return
        val secret = SecretValue.ofText(password)
        val job = launchAttempt { storePassword(secret) }
        // Wiped once stored (see storePassword); this covers an attempt that never got that far.
        job.invokeOnCompletion { secret.close() }
    }

    /** Clears the error and goes back to the first step, if [allowed]; false otherwise. */
    private inline fun restart(crossinline allowed: (ProgressState) -> Boolean): Boolean {
        var restarted = false
        mutableState.update {
            restarted = allowed(it)
            if (restarted) {
                it.copy(step = ProgressStep.Ssh, error = null, actions = emptySet(), askingServerPassword = false)
            } else {
                it
            }
        }
        return restarted
    }

    private fun cancel() {
        closing = true
        synchronized(attemptLock) { attempt?.cancel() }
        // Atomic and not cancellable, so that the disconnect runs even when the scope ends first.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withContext(NonCancellable) { release(cancelling = true) }
            } finally {
                eventChannel.trySend(ProgressEvent.Close)
            }
        }
    }

    /** Replaces the running attempt; the new one is recorded before it can run, then started. */
    private fun launchAttempt(prepare: suspend () -> ServerProfile?): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { runAttempt(prepare) }
        val previous = synchronized(attemptLock) { attempt.also { attempt = job } }
        previous?.cancel()
        job.start()
        return job
    }

    /** Gets the profile with [prepare], connects it and follows the manager until the connect ends. */
    private suspend fun runAttempt(prepare: suspend () -> ServerProfile?) {
        val profile = prepare() ?: return
        if (closing) return
        mutableState.update { it.copy(serverName = profile.name) }
        // Under the lock an older presenter's release holds from its owner check to the end of its
        // disconnect: that disconnect can never fall on this connect.
        connectLock.withLock {
            synchronized(owners) {
                if (left || closing) return
                owners[connection] = token
                activeServer.set(serverId)
                // Publishes the first step at once, so the states that follow are this connect's.
                connection.connect(profile)
            }
        }
        var approvalShown = false
        connection.state.first { state ->
            if (closing || !owns()) return@first true
            when (state) {
                is ConnectionState.HostApproval -> {
                    show(ProgressStep.HostApproval)
                    if (!approvalShown) approvalShown = sendIfOwner(ProgressEvent.OpenHostKey)
                    false
                }
                else -> {
                    approvalShown = false
                    follow(state)
                }
            }
        }
    }

    /** Shows [state]; true once the connect has ended, standing or failed. */
    private fun follow(state: ConnectionState): Boolean = when (state) {
        is ConnectionState.Ssh -> show(ProgressStep.Ssh).let { false }
        // Only a connection that stood reconnects, even if its Connected state was conflated away.
        is ConnectionState.Reconnecting -> {
            stood = true
            show(ProgressStep.Ssh)
            false
        }
        is ConnectionState.Server -> show(ProgressStep.Server).let { false }
        is ConnectionState.Api -> show(ProgressStep.Api).let { false }
        is ConnectionState.HostApproval -> show(ProgressStep.HostApproval).let { false }
        is ConnectionState.Connected -> {
            stood = true
            show(ProgressStep.Connected)
            sendIfOwner(ProgressEvent.Connected) { connectedSent.compareAndSet(false, true) }
            true
        }
        is ConnectionState.Error -> fail(progressFailure(state.error)).let { true }
        // Someone else disconnected; connecting again is up to the user.
        ConnectionState.Idle -> fail(ProgressFailure(ProgressError.Disconnected, setOf(ProgressAction.Retry))).let { true }
        // The app is going away; nothing can connect any more.
        ConnectionState.Closed -> fail(ProgressFailure(ProgressError.Unexpected, emptySet())).let { true }
    }

    /**
     * Sends [event] only if this presenter still owns the manager's connect, and [once] agrees. The
     * check and the send happen under the lock every change of owner takes, so a presenter that a
     * newer one took over in the meantime, even while it showed the step, sends nothing. True if sent.
     */
    private inline fun sendIfOwner(event: ProgressEvent, once: () -> Boolean = { true }): Boolean =
        synchronized(owners) {
            !closing && owners[connection] === token && once() && eventChannel.trySend(event).isSuccess
        }

    private fun show(step: ProgressStep) {
        mutableState.update { it.copy(step = step, error = null, actions = emptySet()) }
    }

    /** Shows [failure] at the step that failed: the error's own, or else the last one seen. */
    private fun fail(failure: ProgressFailure) {
        mutableState.update {
            it.copy(
                step = failure.step ?: it.step,
                error = failure.error,
                actions = failure.actions,
                askingServerPassword = failure.asksPassword,
            )
        }
    }

    /** The profile to connect, as saved now; null, with the error shown, if there is none. */
    private suspend fun loadProfile(): ServerProfile? {
        val profile = try {
            profiles.list().firstOrNull { it.id == serverId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(ProgressFailure(ProgressError.StorageUnavailable, setOf(ProgressAction.Retry)))
            return null
        }
        // Nothing to retry or edit: the server was deleted. Only leaving helps.
        if (profile == null) fail(ProgressFailure(ProgressError.InvalidProfile, emptySet()))
        return profile
    }

    /**
     * Stores [password] under a fresh reference, then the profile pointing at it. Returns the profile
     * saved, or null with the error shown and the profile as it was.
     *
     * [password] is wiped as soon as the store has it, or the write failed: the connect reads it back
     * from the store, so nothing keeps it in memory while the connect waits, for example for a host
     * key decision. The caller wipes it too, for an attempt that ends before this.
     *
     * The credential replaced is kept. Another reference to the same secret may remain, from this or
     * another profile's login or from the key index, and this presenter has no key index to check; a
     * secret deleted while still in use would break a login, an orphan entry harms nothing.
     */
    private suspend fun storePassword(password: SecretValue): ServerProfile? {
        val profile = loadProfile() ?: return null
        val ref = SecretRef(newId())
        try {
            try {
                secrets.put(ref, password)
            } finally {
                password.close()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { deleteQuietly(ref) }
            throw e
        } catch (_: Exception) {
            deleteQuietly(ref)
            return storeFailed()
        }
        val updated = profile.copy(opencodeCredential = ref)
        try {
            profiles.save(updated)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { settleFailedSave(ref) }
            throw e
        } catch (_: Exception) {
            if (!settleFailedSave(ref)) return storeFailed()
        }
        return updated
    }

    /**
     * After a profile save that failed or was cancelled: true if the stored profile points at [ref]
     * all the same. Otherwise [ref] is deleted, unless the profile cannot be read, when it is kept as
     * an orphan rather than risk a profile pointing at nothing.
     */
    private suspend fun settleFailedSave(ref: SecretRef): Boolean {
        val stored = try {
            profiles.list().firstOrNull { it.id == serverId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (stored?.opencodeCredential == ref) return true
        deleteQuietly(ref)
        return false
    }

    private fun storeFailed(): ServerProfile? {
        fail(
            ProgressFailure(
                ProgressError.StorageUnavailable,
                setOf(ProgressAction.EnterServerPassword, ProgressAction.Retry),
            ),
        )
        mutableState.update { it.copy(askingServerPassword = false) }
        return null
    }

    private suspend fun deleteQuietly(ref: SecretRef) {
        try {
            secrets.delete(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Left as an orphan entry: harmless, as nothing points at it.
        }
    }

    private fun owns(): Boolean = synchronized(owners) { owners[connection] === token }

    /** [scope] ended: see the class comment. No connect of this presenter starts after this. */
    private suspend fun leave() = release(cancelling = false)

    /**
     * Gives up this presenter's connect, if the manager still runs it, and disconnects it: always for
     * a Cancel, and only while unfinished when the scope ends. The owner check, the release and the
     * disconnect happen under [connectLock], which a newer presenter takes to connect, so a disconnect
     * decided here always hits this presenter's own connect and never a newer one.
     */
    private suspend fun release(cancelling: Boolean) = connectLock.withLock {
        val disconnect = synchronized(owners) {
            if (!cancelling) left = true
            // Recorded before the Cancel's own cleanup was launched: whichever cleanup comes first
            // honours it, and the other finds nothing left to do.
            val cancel = cancelling || closing
            if (owners[connection] !== token) return@withLock
            owners.remove(connection)
            val current = connection.state.value
            if (cancel) {
                current !is ConnectionState.Idle && current !is ConnectionState.Closed
            } else {
                !stood && current.isUnfinished()
            }
        }
        if (disconnect) connection.disconnect()
    }

    private fun ConnectionState.isUnfinished(): Boolean =
        this is ConnectionState.Ssh || this is ConnectionState.HostApproval ||
            this is ConnectionState.Server || this is ConnectionState.Api

    private companion object {
        /**
         * The progress presenter whose connect each manager runs now. Weak keys, so a manager
         * can go; the values are tokens, so no presenter is kept alive here.
         */
        val owners = WeakHashMap<ConnectionManager, Any>()

        /**
         * Orders every progress presenter's connect against every release: an owner check and the
         * disconnect it decides on run as one step, which no connect can fall between. Held across
         * a disconnect, which waits only for the manager's cleanup, never for this lock.
         */
        val connectLock = Mutex()
    }
}
