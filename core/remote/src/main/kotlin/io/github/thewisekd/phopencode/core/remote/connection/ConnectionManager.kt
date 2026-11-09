package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyCheck
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects the app to one server: SSH, host key approval, `opencode serve` discovery or startup,
 * and the API through the SSH tunnel; then watches the connection and reconnects after a drop.
 * [state] tells where it is; see [ConnectionState].
 *
 * Reconnecting: a connection that stood drops when its SSH session ends
 * ([ConnectionError.Disconnected]) or when `opencode serve` stops answering while SSH stands
 * ([ConnectionError.ServerLost]): the manager keeps an event stream of its own open on each
 * connection's client, and that stream dropping or ending is the sign. Either way the manager
 * releases the client and session and tries again after [ReconnectPolicy]'s delays, through every
 * step, the server step included, so a server that is gone is found missing and started again.
 * Only [ConnectionError.retryable] failures are tried again, up to [ReconnectPolicy.maxAttempts];
 * any other failure, and every failure of a first connect, ends in [ConnectionState.Error], since
 * those need the user. A [disconnect], [close] or new [connect] is never taken for a drop.
 *
 * What a drop may do on the server: nothing but what a first connect does. The manager never stops,
 * kills or replaces a process, an `opencode serve` that runs but does not answer included, whoever
 * started it, and never reboots or resets the machine. Only when discovery finds no `opencode
 * serve` running at all does it start a new one, exactly as a first connect would; a server that
 * runs but does not answer is just tried again with backoff.
 *
 * Resources: everything a connection opens (the SSH session, with its processes and tunnels, the
 * API client with the manager's event stream, and every client [openSftp] gave out on it) belongs
 * to it, and is closed when the connection drops or fails, and when [connect] replaces it. Login
 * secrets are wiped right after use.
 *
 * Closing: [disconnect] and [close] suspend until every connection this manager started has
 * finished, its cleanup included: credentials wiped, client and session closed. Concurrent calls
 * all wait for that same moment. Cancelling their caller does not cut the wait short. Cancelling
 * [scope] closes the manager too, whatever it is doing, idle included; the state turns
 * [ConnectionState.Closed] at once and the connections finish their cleanup on their own.
 *
 * Stray cancellations: only the end of [scope] closes the manager. A `CancellationException` that
 * comes out of a step while neither a stop nor the scope cancelled the connection (a timeout
 * leaking from a nested `withTimeout`, a library's `java.util.concurrent.CancellationException`, a
 * cancelled child) is a failure of that step: the connection ends in [ConnectionState.Error] with
 * [ConnectionError.Unexpected] carrying it, and the manager stays usable, so a later [connect]
 * works. It is not retried; see [ConnectionError.Unexpected].
 *
 * Threads: nothing is ever closed on the thread that calls [connect], [disconnect], [close],
 * [acceptChangedHostKey] or [openSftp], which may be the main thread: closing an SSH resource
 * writes to the network. A connection's cleanup runs in its own job on [scope] and, once stopped,
 * also on [Dispatchers.IO]. [connect] returns without waiting for the connection it replaces to
 * finish closing; [disconnect] and [close] still wait, as above. A caller's own close of an
 * [openSftp] client runs on the caller's thread; the SSH layer sends its packets elsewhere.
 *
 * No state or error carries an address or a secret. Nothing is logged unless [diagnostics] are
 * on; then each end of a connection is recorded in [lastEnd] and logged through the end log.
 *
 * @param scope Runs the connections; it must have a [Job]. Cancelling it closes the manager. Its
 *   dispatcher must not be Main or Unconfined: a cancelled connection releases its resources,
 *   network closes included, on that dispatcher.
 * @param diagnostics Off by default. When on, see [lastEnd]; the traces may hold an address or a
 *   path, from a failure's message, but never a credential (see [Diagnostics]).
 * @param endLog Where each recorded end is logged while [diagnostics] are on.
 */
class ConnectionManager(
    private val connector: SshConnector,
    private val secrets: SecretStore,
    private val knownHosts: KnownHostsStore,
    private val servers: ServerResolver,
    private val clients: OpencodeClientFactory,
    private val scope: CoroutineScope,
    private val options: ConnectionOptions = ConnectionOptions(),
    val diagnostics: Diagnostics = Diagnostics.Off,
    private val endLog: ConnectionEndLog = ConnectionEndLog.JavaLogging,
) {
    private val lock = Any()
    private val scopeJob: Job = requireNotNull(scope.coroutineContext[Job]) { "The scope has no Job" }
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** Guarded by [lock]: the connection [state] speaks for. */
    private var current: Run? = null

    /** Guarded by [lock]: every connection whose job has not completed, [current] included. */
    private val runs = LinkedHashSet<Run>()

    /** Guarded by [lock]. */
    private var closed = false
    private var generation = 0L

    /**
     * Guarded by [lock]: the known-host write under way, if any, completed when it ends. A write
     * claims it in the same locked step that checks its request is still current; a run created
     * meanwhile starts only after it, and [disconnect] and [close] wait for the run that writes.
     */
    private var storeWrite: CompletableDeferred<Unit>? = null

    /** Completes once the manager is closed and every run has completed. */
    private val fullyClosed = CompletableDeferred<Unit>()

    /** Null until registered; the handler may already have run by then. */
    @Volatile
    private var scopeWatch: DisposableHandle? = null

    /** Declared before `init`, whose scope handler may record at once. */
    @Volatile
    private var lastEndTrace: Throwable? = null

    /**
     * Only while [diagnostics] are on, else always null: why the connection last ended, as a trace
     * captured at that moment. A [ConnectionEndTrace] whose stack shows the caller for
     * [disconnect], [close], a [connect] that replaced a connection, the end of the scope (with its
     * cancellation cause) and a drop; or the failure itself, when a connect or a connection failed
     * with one. Read-only; a later end replaces it.
     */
    val lastEnd: Throwable? get() = lastEndTrace

    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /**
     * Test hook: runs in a connection's job just before a known-host write takes its last check
     * that the request is still current, so a test can hold the write in that window.
     */
    @Volatile
    internal var beforeStoreWrite: (suspend () -> Unit)? = null

    /**
     * Test hook: runs on the caller's thread after [connect] has changed the state and recorded
     * the end of the connection it replaced, outside the lock and before that end is logged, so a
     * test can close the manager in between.
     */
    @Volatile
    internal var afterConnectSwitched: (() -> Unit)? = null

    init {
        // Runs immediately for a scope that already ended.
        scopeWatch = scopeJob.invokeOnCompletion { cause ->
            closeNow { ConnectionEndTrace("closed by the end of the manager's scope", cause) }
        }
    }

    /** Connects to [profile], ending any current connection first. Does nothing once closed. */
    fun connect(profile: ServerProfile) {
        val run: Run
        var ended: Throwable? = null
        val previous = synchronized(lock) {
            if (closed) return
            // The job exists before anyone can see this run, so a stop at any moment cancels it.
            run = Run(profile)
            // A scope that ended just now cancels the job at once, which closes the manager.
            if (closed) return
            val previous = current
            current = run
            // The end first, so that a collector woken by the state reads it.
            if (previous != null) ended = recordEnd { ConnectionEndTrace("connect() replaced the current connection") }
            mutableState.value = ConnectionState.Ssh()
            previous
        }
        afterConnectSwitched?.invoke()
        logEnd(ended)
        previous?.stop()
        run.start()
    }

    /**
     * Approves the key of [shown], the [ConnectionState.HostApproval] the user saw, and stores it as
     * known for [ConnectionState.HostApproval.server]'s endpoint; the connect then goes on. True
     * once the key is stored. False, and nothing is stored, unless [shown] is the very state [state]
     * holds now: a request from an earlier connect, one already answered, or one ended by a
     * disconnect, connect or close is never answered, even when an equal-looking request stands
     * now. Also false when the request ends before its store write starts, or the store refuses
     * the key (another key was approved meanwhile, or storage failed); [state] then tells why.
     *
     * The last check that the request is current and the start of the store write are one step
     * for [connect], [disconnect] and [close]: one that comes first stops the write, and one that
     * comes after waits for it. Suspends until the write is decided, not until the connect ends.
     */
    suspend fun approveHostKey(shown: ConnectionState.HostApproval): Boolean {
        val pending = synchronized(lock) {
            current?.pending?.takeIf { it.shown === shown && mutableState.value === shown }
        } ?: return false
        if (!pending.decision.complete(true)) return false
        return pending.stored.await()
    }

    /**
     * Rejects the key of [shown]; the connection ends with [ConnectionError.HostKeyRejected]. False,
     * and nothing happens, unless [shown] is the very state [state] holds now. Writes nothing.
     */
    fun rejectHostKey(shown: ConnectionState.HostApproval): Boolean {
        val pending = synchronized(lock) {
            current?.pending?.takeIf { it.shown === shown && mutableState.value === shown }
        } ?: return false
        return pending.decision.complete(false)
    }

    /**
     * Approves [key] for the host waiting in [ConnectionState.HostApproval] and stores it as known.
     * False when no approval waits for this very key. Answers whichever request waits now, and
     * returns before the store write, which still happens only while the request is current;
     * prefer the overload that takes the shown state.
     */
    fun approveHostKey(key: HostKey): Boolean = decide(key, approved = true)

    /** Rejects [key]; the connection ends with [ConnectionError.HostKeyRejected]. False as for [approveHostKey]. */
    fun rejectHostKey(key: HostKey): Boolean = decide(key, approved = false)

    private fun decide(key: HostKey, approved: Boolean): Boolean {
        val pending = synchronized(lock) { current?.pending } ?: return false
        return pending.key == key && pending.decision.complete(approved)
    }

    /**
     * The user's explicit decision to trust the changed key that [shown] reports: a
     * [ConnectionState.Error] holding a [ConnectionError.HostKeyChanged]. Replaces the approved key
     * with the presented one at the endpoint the connection used,
     * [ConnectionError.HostKeyChanged.server], whatever the profile says by now, and then connects
     * the same profile again, as it was when the failed connect began.
     *
     * Bound to [shown]: under the manager's lock it checks that [state] still holds this very state
     * object, published by the current connect, and in the same step takes over as a new connect
     * ([ConnectionState.Ssh]). So a second call with the same state, or a call after a disconnect, a
     * new connect, a newer error or a close, does nothing and gives [HostKeyReplacement.Stale]. The
     * store write runs inside that new connect, and its last check that the connect is still
     * current and the start of the write are one step for [connect], [disconnect] and [close]: one
     * that comes first stops the write, and one that comes after waits for the write to end and
     * finds the key stored. No reconnect follows a write that such a call interrupted. The store
     * replaces only while the approved key is still [ConnectionError.HostKeyChanged.approved]
     * (else [HostKeyReplacement.Mismatch]).
     *
     * Suspends until the replace is decided, not until the reconnect ends; [state] tells the rest.
     * Cancelling the caller does not stop the replace.
     */
    suspend fun acceptChangedHostKey(shown: ConnectionState.Error): HostKeyReplacement {
        val changed = shown.error as? ConnectionError.HostKeyChanged ?: return HostKeyReplacement.Stale
        val server = changed.server ?: return HostKeyReplacement.Stale
        val replacement = Replacement(
            endpoint = server.endpoint,
            expected = changed.approved,
            replacement = HostKeyFingerprint(changed.key.fingerprintSha256),
        )
        val run: Run
        val previous = synchronized(lock) {
            if (closed) return HostKeyReplacement.Stale
            val previous = current
            if (previous == null || previous.shown !== shown || mutableState.value !== shown) {
                return HostKeyReplacement.Stale
            }
            if (previous.profile.id != server.profileId) return HostKeyReplacement.Stale
            run = Run(previous.profile, replacement)
            // As in [connect]: a scope that ended just now cancels the job at once.
            if (closed) return HostKeyReplacement.Stale
            current = run
            mutableState.value = ConnectionState.Ssh()
            previous
        }
        previous.stop()
        run.start()
        return replacement.outcome.await()
    }

    /**
     * Reconnects now: a standing connection is dropped and made again, and a wait before a
     * reconnect attempt ends at once. For when the app learns of a problem first, for example a
     * network change; a server or event-stream drop is noticed without it. Does nothing while idle,
     * stopped or connecting.
     */
    fun reconnect() {
        synchronized(lock) { current }?.kicks?.trySend(Unit)
    }

    /**
     * Opens an SFTP client confined to [root] (see [SftpClient]) on the SSH session of the
     * connection that stands right now, that is while [state] is [ConnectionState.Connected].
     *
     * The client belongs to that connection: it is closed when the connection drops, when
     * [reconnect], [disconnect], [connect] or [close] ends it, or when the manager's scope ends, and
     * a later connection needs a new one. The caller may close it earlier; it is closed only once
     * either way. An open that finishes after its connection ended closes the client and fails.
     * Cancelling the call leaves nothing open.
     *
     * @throws NotConnectedException When no connection stands, or it ended during the open.
     * @throws io.github.thewisekd.phopencode.core.ssh.api.SshException.Sftp When [root] is refused,
     *   missing or not readable; the connection stays.
     * @throws io.github.thewisekd.phopencode.core.ssh.api.SshException.Disconnected When the SSH
     *   session ends during the open.
     */
    suspend fun openSftp(root: String): SftpClient {
        val (run, live) = synchronized(lock) {
            val run = current
            val live = run?.live ?: throw NotConnectedException()
            run to live
        }
        // Cancellation inside the session's open closes its half-open channel; after it returns
        // there is no suspension point before the client is tracked.
        return run.track(live, live.session.openSftp(root))
    }

    /**
     * Runs [block] bound to the connection that stands right now, that is while [state] is
     * [ConnectionState.Connected]. Everything [block] gets comes from that one connection, through
     * a [BoundConnection]: its session's command runner, its API client's file search, its SFTP
     * opens. The session itself is never handed out.
     *
     * When that connection drops, or [reconnect], [disconnect], [connect] or [close] ends it, while
     * [block] runs, [block] is cancelled and the call fails with [NotConnectedException]; so does a
     * [block] that returns, or fails, only after its connection ended. A later connection, with a
     * new [ConnectionState.Connected.generation] or another server, is never used.
     *
     * The manager adds nothing to what [block] runs: no signal, no process ending, no `sudo`.
     *
     * @throws NotConnectedException When no connection stands, or it ended before [block] finished.
     */
    suspend fun <T> withConnection(block: suspend (BoundConnection) -> T): T {
        val (run, live) = synchronized(lock) {
            val run = current
            val live = run?.live ?: throw NotConnectedException()
            run to live
        }
        val bound = Bound(run, live)
        val result = try {
            coroutineScope {
                val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                    live.gone.await()
                    throw NotConnectedException()
                }
                try {
                    block(bound)
                } finally {
                    watch.cancel()
                }
            }
        } catch (failure: NotConnectedException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!stands(run, live)) throw NotConnectedException()
            throw failure
        }
        if (!stands(run, live)) throw NotConnectedException()
        return result
    }

    /** Whether [live] is still the connection [run] publishes, and [run] still the current one. */
    private fun stands(run: Run, live: Live): Boolean = synchronized(lock) { current === run && run.live === live }

    /**
     * The [BoundConnection] of one [withConnection] call: [live] of [run], captured once. The
     * manager's current connection is read only to refuse, never to pick a session.
     */
    private inner class Bound(private val run: Run, private val live: Live) : BoundConnection {
        override val exec: RemoteExec get() = live.session.exec
        override val fs: FsApi get() = live.client.fs
        override val server: ConnectionServer get() = live.server
        override val generation: Long = synchronized(lock) { live.generation }
        override val opencode: OpencodeLogin? get() = live.opencode

        override suspend fun readOpencodePassword(): SecretValue? {
            val ref = live.opencode?.passwordRef ?: return null
            val check = live.passwordCheck ?: return null
            val value = try {
                secrets.readOrNull(ref)
            } catch (_: ConnectionException) {
                // Unreachable or unreadable storage: the caller only needs to know there is none.
                null
            } ?: return null
            // A secret changed since the connect is not one the server accepted: unreadable.
            if (check.matches(value)) return value
            value.close()
            return null
        }

        override suspend fun openSftp(root: String): SftpClient {
            if (!stands(run, live)) throw NotConnectedException()
            // Opened on the captured session only. A stop or drop meanwhile makes [Run.track] close
            // the client and fail.
            return run.track(live, live.session.openSftp(root))
        }

        override fun toString(): String = "BoundConnection(generation=$generation)"
    }

    /**
     * Ends the connection and returns to [ConnectionState.Idle] once every connection has finished
     * its cleanup. After [close], it waits for the close to finish instead.
     */
    suspend fun disconnect() {
        var ended: Throwable? = null
        val stopping = synchronized(lock) {
            if (closed) null else {
                // The end first, so that a collector woken by the state reads it.
                ended = recordEnd { ConnectionEndTrace("disconnect() called") }
                mutableState.value = ConnectionState.Idle
                current = null
                runs.toList()
            }
        }
        if (stopping == null) {
            awaitClosed()
            return
        }
        logEnd(ended)
        val releases = stopping.map(Run::stop)
        withContext(NonCancellable) {
            stopping.forEach { it.join() }
            releases.joinAll()
        }
    }

    /**
     * Ends the connection for good. Returns once every connection has finished its cleanup, also
     * when another close or a cancelled scope started the close. Safe to call again.
     */
    suspend fun close() {
        closeNow { ConnectionEndTrace("close() called") }
        awaitClosed()
    }

    private suspend fun awaitClosed() {
        withContext(NonCancellable) { fullyClosed.await() }
    }

    /**
     * Marks the manager closed and stops every run, without waiting; [fullyClosed] completes when
     * the last run has completed. Never suspends, so a scope's completion handler can call it.
     * [reason] makes the trace for [lastEnd], only when this call closes and diagnostics are on.
     */
    private fun closeNow(reason: () -> Throwable) {
        var ended: Throwable? = null
        val stopping = synchronized(lock) {
            if (closed) return
            closed = true
            // The end first, so that a collector woken by the state reads it.
            ended = recordEnd(reason)
            mutableState.value = ConnectionState.Closed
            current = null
            if (runs.isEmpty()) fullyClosed.complete(Unit)
            runs.toList()
        }
        logEnd(ended)
        scopeWatch?.dispose()
        stopping.forEach(Run::stop)
    }

    /**
     * Called under [lock], in the same critical section as the state change the end explains and
     * just before its write, so the order of the traces is the order of the state changes (an end
     * recorded for an older change never replaces a newer one), and a collector woken by the new
     * state, even on the writer's thread, reads the end that explains it. With [diagnostics] on, makes [trace] now, so that its
     * stack is the caller's, keeps it as [lastEnd] and returns it, for [logEnd] to log once the
     * lock is released. With diagnostics off it returns null, and [trace] is never called.
     */
    private fun recordEnd(trace: () -> Throwable): Throwable? {
        if (!diagnostics.enabled) return null
        return trace().also { lastEndTrace = it }
    }

    /** Logs [ended], an end [recordEnd] returned; outside [lock]. Does nothing for null. */
    private fun logEnd(ended: Throwable?) {
        if (ended == null) return
        try {
            endLog.ended(ended)
        } catch (_: Exception) {
            // Logging is best effort; it must never change how a connection ends.
        }
    }

    /** Called once per run, when its job has completed: cleanup has run by then. */
    private fun completed(run: Run) {
        synchronized(lock) {
            runs.remove(run)
            if (closed && runs.isEmpty()) fullyClosed.complete(Unit)
        }
    }

    private class PendingApproval(val key: HostKey, val decision: CompletableDeferred<Boolean>) {
        /** Guarded by [lock]: the [ConnectionState.HostApproval] published for this request. */
        var shown: ConnectionState? = null

        /** True once an approved key is stored; false when it is not, whyever. */
        val stored = CompletableDeferred<Boolean>()
    }

    /** A changed key to store before a [Run] connects; [outcome] completes once it is decided. */
    private class Replacement(
        val endpoint: HostEndpoint,
        val expected: HostKeyFingerprint,
        val replacement: HostKeyFingerprint,
    ) {
        val outcome = CompletableDeferred<HostKeyReplacement>()
    }

    private class Live(
        val session: SshSession,
        val client: OpencodeClient,
        val info: ServerInfo,
        val server: ConnectionServer,
        /** Where `opencode serve` is and the ref of the password it accepted; never the password. */
        val opencode: OpencodeLogin?,
        /** Recognises the accepted password when it is read again through [opencode]'s ref. */
        val passwordCheck: PasswordCheck?,
    ) {
        /** Guarded by the run's held lock: the [openSftp] clients still open on this connection. */
        val sftp = ArrayList<TrackedSftp>()

        /** Guarded by the run's held lock: the connection dropped; no client is added any more. */
        var ended = false

        /** Completes once the connection dropped or its run stopped; [withConnection] watches it. */
        val gone = CompletableDeferred<Unit>()

        /** Guarded by [lock]: the generation it was published with, set before anyone can see it. */
        var generation = 0L
    }

    /**
     * An [openSftp] client, handed to the caller. Its first [close], by the caller or the manager,
     * takes it out of the connection's resources with [untrack] and closes the real client; later
     * ones do nothing.
     *
     * Deciding and closing both happen under [lock], the run's held lock, which the manager's
     * cleanup also holds while it closes. So whichever side comes first closes the real client
     * completely before the other goes on: a cleanup never returns while a caller's close of this
     * client is still under way, and the real client is closed once.
     */
    private class TrackedSftp(
        private val inner: SftpClient,
        private val lock: Any,
        private val untrack: (TrackedSftp) -> Unit,
    ) : SftpClient by inner {
        /** Guarded by [lock]. */
        private var closed = false

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                untrack(this)
                inner.close()
            }
        }
    }

    /** Why a connection that was published as connected ended, and whether its event stream opened. */
    private class Drop(val cause: ConnectionError, val stood: Boolean, val trace: Throwable?)

    /** What [Run.watchServer] saw end the server, and the failure behind it, if any. */
    private class ServerEnd(val error: ConnectionError, val failure: Throwable?)

    /**
     * One [connect], or one [acceptChangedHostKey], which first stores [replacement]: its job, what
     * it holds open, and its waiting approval.
     */
    private inner class Run(val profile: ServerProfile, private val replacement: Replacement? = null) {
        private val held = ArrayList<AutoCloseable>()
        /**
         * Set without [held], so that [stop] never waits for a cleanup that holds it across a
         * close. Read under [held] where a resource is taken in, so a resource that got in before
         * the stop is in [held] for the release that follows it.
         */
        @Volatile
        private var stopped = false

        /** Guarded by [lock]: the last state this run published, as [state] holds it. */
        var shown: ConnectionState? = null

        @Volatile
        var pending: PendingApproval? = null

        /** Guarded by [lock]: the connection published as [ConnectionState.Connected], while it stands. */
        var live: Live? = null

        val kicks = Channel<Unit>(Channel.CONFLATED)

        /** The known-host write under way when this run was made; the run starts after it ends. */
        private val after: CompletableDeferred<Unit>?

        /** Lazy, so that a [stop] before [start] means it never runs. */
        private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // A connect never reads known hosts while an older run still writes them.
                after?.await()
                if (replacement == null || replaceKey(replacement)) execute(profile)
            } finally {
                // Whatever ended the job, a stop, a stray cancellation or a failure while connected,
                // the connection it published is unreachable before anything closes and before
                // [ended] publishes: [openSftp] and [withConnection] refuse, and its SFTP clients close.
                synchronized(lock) { live }?.let(::endLive)
                releaseAll()
            }
        }

        init {
            after = synchronized(lock) {
                runs += this
                storeWrite
            }
            job.invokeOnCompletion { cause ->
                if (cause != null) ended(cause)
                // A replace that was stopped, or never started, has decided nothing yet.
                replacement?.outcome?.complete(HostKeyReplacement.Stale)
                completed(this)
            }
        }

        suspend fun join() = job.join()

        /**
         * The job ended by something other than a normal return. A stop is expected. A cancellation
         * closes the manager only when the scope really ended (cancelled, or completing): any
         * other one is a stray that escaped the steps, and like any other failure it is an
         * [ConnectionError.Unexpected] error that leaves the manager usable. By then the job's
         * cleanup has made a published connection unreachable ([live] is null, its watchers ended,
         * its SFTP clients closed) and released every resource, so nothing a
         * [ConnectionState.Connected] exposed outlives it, whichever way this goes.
         */
        private fun ended(cause: Throwable) {
            if (stopped) return
            if (cause is CancellationException && !scopeJob.isActive) {
                closeNow { ConnectionEndTrace("closed by the end of the manager's scope under a connection", cause) }
            } else {
                publishEnd(ConnectionState.Error(ConnectionError.Unexpected(cause))) { cause }
            }
        }

        /**
         * Whether a `CancellationException` caught in this run really ends it: the run was stopped,
         * or the coroutine that caught it is cancelled (by a stop, the end of the scope, or its
         * own parent). Otherwise it is a stray, such as a timeout leaking from a nested
         * `withTimeout` or a library's `java.util.concurrent.CancellationException`, and the step
         * that threw it failed.
         */
        private suspend fun stopping(): Boolean = stopped || !currentCoroutineContext().isActive

        /** Does nothing once [stop] has cancelled the job. */
        fun start() {
            job.start()
        }

        /**
         * Publishes [state] unless a newer [connect], [disconnect] or [close] took over. Before the
         * write, under [lock], it records and passes to [published] the object [state] will hold:
         * a state flow keeps an equal earlier object instead of a new one. Recording first matters
         * because the write may resume an unconfined collector on this very thread, which may answer
         * the state at once.
         */
        fun publish(state: ConnectionState, published: (ConnectionState) -> Unit = {}) {
            synchronized(lock) {
                if (current === this) {
                    val before = mutableState.value
                    val held = if (before == state) before else state
                    shown = held
                    published(held)
                    mutableState.value = held
                }
            }
        }

        /**
         * Publishes [state], as [publish] does, and when it was published records [trace] as the
         * connection's end in the same locked step, before the state write (see [recordEnd]), then
         * logs it. A state equal to the one already held is no new end: observers get no update
         * for it, so nothing is recorded and the end recorded with that state stays.
         */
        fun publishEnd(state: ConnectionState, trace: () -> Throwable) {
            var ended: Throwable? = null
            publish(state) { held -> if (held !== mutableState.value) ended = recordEnd(trace) }
            logEnd(ended)
        }

        /**
         * Runs [write], a known-host store call, only while this run is current; null when it is
         * not. The check and the claim of [storeWrite] are one locked step, which [connect],
         * [disconnect] and [close] also take to end a run: one that comes first makes the check
         * fail, so nothing is written; one that comes after finds the write claimed. The write then
         * runs to its end whatever stops the run, outside the lock, so that [disconnect] and
         * [close], which wait for the run, and a new run, which waits for the claim, see its result.
         *
         * The end of the manager's scope cancels the run's job before [closeNow] runs, so the check
         * also needs the job active: a cancelled run never starts a write. A cancellation that
         * comes just after the check finds the write started, and the scope completes only after
         * it, as for a [close].
         */
        suspend fun <T> writeKnownHost(write: suspend () -> T): T? {
            beforeStoreWrite?.invoke()
            val done = CompletableDeferred<Unit>()
            val earlier = synchronized(lock) {
                if (current !== this || !job.isActive) return null
                val earlier = storeWrite
                storeWrite = done
                earlier
            }
            try {
                return withContext(NonCancellable) {
                    earlier?.await()
                    write()
                }
            } finally {
                synchronized(lock) { if (storeWrite === done) storeWrite = null }
                done.complete(Unit)
            }
        }

        /**
         * Stores [replacement] at its endpoint, unless this run was stopped first. True when the
         * profile is to connect next; false when the run ends here.
         */
        private suspend fun replaceKey(replacement: Replacement): Boolean {
            val update = try {
                // Null: a disconnect, connect or close since the take-over; the store is left alone.
                writeKnownHost {
                    knownHosts.replace(replacement.endpoint, replacement.expected, replacement.replacement)
                } ?: return false
            } catch (failure: Exception) {
                if (failure is CancellationException && stopping()) throw failure
                val error = if (failure is IOException) {
                    ConnectionError.StorageUnavailable
                } else {
                    ConnectionError.Unexpected(failure)
                }
                // The state first, so that a caller who resumes at once finds it told.
                publishEnd(ConnectionState.Error(error)) { failure }
                replacement.outcome.complete(HostKeyReplacement.Failed(error))
                return false
            }
            replacement.outcome.complete(
                when (update) {
                    HostKeyUpdate.Stored -> HostKeyReplacement.Replaced
                    is HostKeyUpdate.Rejected -> HostKeyReplacement.Mismatch(update.current)
                },
            )
            // A stop during the write: the write stands, but no connect follows it.
            currentCoroutineContext().ensureActive()
            return synchronized(lock) { current === this }
        }

        /**
         * Takes [resource] into this run; one arriving after [stop] is closed at once, and the
         * cancellation thrown then is a stop, never a stray: [stopped] is already set.
         */
        fun <T : AutoCloseable> hold(resource: T): T {
            synchronized(held) {
                if (!stopped) {
                    held += resource
                    return resource
                }
            }
            closeQuietly(resource)
            throw CancellationException("The connection was stopped")
        }

        /**
         * Takes [client], just opened on [live]'s session, into this run and [live]. One arriving
         * after a stop or after [live] dropped is closed, on [Dispatchers.IO] and not on the
         * caller's thread, and the open fails once it is closed.
         */
        suspend fun track(live: Live, client: SftpClient): SftpClient {
            val tracked = TrackedSftp(client, held) { self ->
                held.remove(self)
                live.sftp.remove(self)
            }
            synchronized(held) {
                if (!stopped && !live.ended) {
                    held += tracked
                    live.sftp += tracked
                    return tracked
                }
            }
            withContext(NonCancellable + Dispatchers.IO) { closeQuietly(client) }
            throw NotConnectedException()
        }

        /** Publishes [live] as connected, unless a newer [connect], [disconnect] or [close] took over. */
        private fun publishConnected(live: Live, generation: Long) {
            synchronized(lock) {
                if (current === this) {
                    live.generation = generation
                    this.live = live
                    // Recorded first, as in [publish]; a new generation is never equal to the state held.
                    val connected = ConnectionState.Connected(live.client, live.info, generation, live.server)
                    shown = connected
                    mutableState.value = connected
                }
            }
        }

        /** [live] dropped: [openSftp] no longer reaches it, and its SFTP clients close, newest first. */
        private fun endLive(live: Live) {
            synchronized(lock) { if (this.live === live) this.live = null }
            live.gone.complete(Unit)
            synchronized(held) {
                live.ended = true
                val clients = live.sftp.toList()
                live.sftp.clear()
                clients.asReversed().forEach(::closeQuietly)
            }
        }

        /*
         * Resources are closed under the [held] lock, never just taken out of the list first: a
         * [stop] from another thread then waits for a close in progress, so that [close] and
         * [disconnect] return only once nothing is open.
         */

        fun release(resource: AutoCloseable?) {
            if (resource == null) return
            synchronized(held) {
                held.remove(resource)
                closeQuietly(resource)
            }
        }

        /** Newest first, so the client closes before the session that carries it. */
        private fun releaseAll() {
            endWatchers()
            synchronized(held) {
                // A copy: closing an SFTP client takes it out of [held].
                val all = held.toList()
                held.clear()
                all.asReversed().forEach(::closeQuietly)
            }
        }

        /** Ends the [withConnection] calls on the live connection, if any. */
        private fun endWatchers() {
            synchronized(lock) { live }?.gone?.complete(Unit)
        }

        /**
         * Stops the run and returns at once. The release runs on [Dispatchers.IO], never on the
         * caller's thread, since closing writes to the network; the job's own cleanup releases
         * too, so [join] returns only once nothing is open. The returned job ends with that release.
         */
        fun stop(): Job {
            stopped = true
            pending?.decision?.complete(false)
            job.cancel()
            endWatchers()
            return RELEASES.launch { releaseAll() }
        }

        private suspend fun execute(profile: ServerProfile) {
            val policy = options.reconnect
            var attempt = 0
            var everConnected = false
            while (true) {
                var failure: Throwable? = null
                val error = try {
                    val live = establish(profile, attempt)
                    everConnected = true
                    // A request made before this connection stood is about an older one. Dropped
                    // before the state is published, so a request answering that state counts.
                    while (kicks.tryReceive().isSuccess) Unit
                    publishConnected(live, nextGeneration())
                    val dropped = awaitDrop(live)
                    endLive(live)
                    release(live.client)
                    release(live.session)
                    // Only a connection that really stood starts the attempts over. One whose event
                    // stream never opened counts as a failed attempt, or a server that answers its
                    // info call but nothing else would be retried forever.
                    if (dropped.stood) attempt = 0
                    failure = dropped.trace
                    dropped.cause
                } catch (thrown: ConnectionException) {
                    // The exception itself when it wraps nothing: its stack shows the step.
                    failure = thrown.cause ?: thrown
                    thrown.error
                } catch (cancelled: CancellationException) {
                    // A stray that no step mapped; a stop or the scope's end passes.
                    if (stopping()) throw cancelled
                    failure = cancelled
                    ConnectionError.Unexpected(cancelled)
                }
                val ended = failure
                val trace = { ended ?: ConnectionEndTrace("ended: ${error.javaClass.simpleName}") }
                if (!everConnected || !error.retryable) {
                    publishEnd(ConnectionState.Error(error), trace)
                    return
                }
                attempt++
                val limit = policy.maxAttempts
                if (limit != null && attempt > limit) {
                    publishEnd(ConnectionState.Error(error), trace)
                    return
                }
                val wait = policy.delayBefore(attempt)
                publishEnd(ConnectionState.Reconnecting(attempt, wait, error), trace)
                withTimeoutOrNull(wait) { kicks.receive() }
            }
        }

        private fun nextGeneration(): Long = synchronized(lock) { ++generation }

        /**
         * Returns why the connection ended: the session ended, the server stopped answering (see
         * [watchServer]), or [reconnect] was called; and whether the connection stood, which is
         * when the watch's event stream opened. A stop cancels this instead, so a deliberate
         * [disconnect] or [close] never comes back as a drop.
         */
        private suspend fun awaitDrop(live: Live): Drop {
            val stood = AtomicBoolean(false)
            val traced = diagnostics.enabled
            val end = try {
                coroutineScope {
                    val sshDropped = async { live.session.connected.first { !it } }
                    val serverDropped = async { watchServer(live.client) { stood.set(true) } }
                    fun disconnected(why: String) = ServerEnd(
                        ConnectionError.Disconnected,
                        if (traced) ConnectionEndTrace("dropped: Disconnected; $why") else null,
                    )
                    val end = select<ServerEnd> {
                        sshDropped.onAwait { disconnected("the SSH session reported it is no longer connected") }
                        serverDropped.onAwait { it }
                        kicks.onReceive { disconnected("reconnect() asked for a new connection") }
                    }
                    sshDropped.cancel()
                    serverDropped.cancel()
                    end
                }
            } catch (cancelled: CancellationException) {
                // A watch that ended cancelled while this run was not: the connection is dropped
                // as after any failure, and the caller releases it.
                if (stopping()) throw cancelled
                ServerEnd(ConnectionError.Unexpected(cancelled), cancelled)
            }
            // The server's stream breaks with the SSH connection that carries it; that is an SSH drop.
            val sshGone = !live.session.connected.value
            val cause = if (sshGone) ConnectionError.Disconnected else end.error
            val trace = when {
                !traced -> null
                sshGone && end.error != ConnectionError.Disconnected -> ConnectionEndTrace(
                    "dropped: Disconnected; the SSH session ended under ${end.error.javaClass.simpleName}",
                    end.failure,
                )
                else -> end.failure ?: ConnectionEndTrace("dropped: ${cause.javaClass.simpleName}")
            }
            return Drop(cause, stood.get(), trace)
        }

        /**
         * Watches the server through an event stream of the manager's own, and returns once it is
         * gone. The stream is cold, so this collection is a connection of its own and takes no
         * event from the app's collectors. It asks only for [WATCHED_EVENTS], a rare type from the
         * spec's `types` enum, which keeps the traffic down; `server.connected` is not in that enum,
         * so a validating server might refuse it, but the server sends it first on every stream
         * whatever the filter, and the client emits [ServerEvent.Connected] for each connection.
         *
         * The client's stream retries by itself and emits [ServerEvent.Disconnected] on each drop;
         * the first one, the server's `location.shutdown` notice, or the stream completing means
         * the server stopped answering, and the manager reconnects through the server step
         * instead. A failure, the stream failing to open included, ends as that API error; a
         * cancellation while this watch is still active is a failure too, [ConnectionError.Unexpected].
         * Only the connection is dropped: nothing on the server is stopped.
         *
         * @param opened Called on each [ServerEvent.Connected], that is once the stream is open.
         */
        private suspend fun watchServer(client: OpencodeClient, opened: () -> Unit): ServerEnd = try {
            var last: ServerEvent? = null
            client.events.events(WATCHED_EVENTS)
                .onEach { if (it is ServerEvent.Connected) opened() }
                .takeWhile { event ->
                    last = event
                    !event.endsServer()
                }
                .collect { }
            val trace = if (diagnostics.enabled) {
                val seen = last?.takeIf { it.endsServer() }?.javaClass?.simpleName ?: "the stream completing"
                ConnectionEndTrace("dropped: ServerLost; the server's event stream ended with $seen")
            } else {
                null
            }
            ServerEnd(ConnectionError.ServerLost, trace)
        } catch (cancelled: CancellationException) {
            if (stopping()) throw cancelled
            ServerEnd(ConnectionError.Unexpected(cancelled), cancelled)
        } catch (failure: ApiException) {
            ServerEnd(ConnectionError.Api(failure.error), failure)
        } catch (failure: Exception) {
            ServerEnd(ConnectionError.Unexpected(failure), failure)
        }

        /**
         * Turns every failure of a server or API step into [ConnectionException]. A cancellation
         * passes only when it is a stop (see [stopping]); a stray one is [ConnectionError.Unexpected].
         */
        private suspend fun <T> mapFailures(block: suspend () -> T): T = try {
            block()
        } catch (failure: ConnectionException) {
            throw failure
        } catch (failure: SshException) {
            throw ConnectionException(sshError(failure), failure)
        } catch (failure: ApiException) {
            throw ConnectionException(ConnectionError.Api(failure.error), failure)
        } catch (failure: Exception) {
            if (failure is CancellationException && stopping()) throw failure
            throw ConnectionException(ConnectionError.Unexpected(failure), failure)
        }

        private suspend fun establish(profile: ServerProfile, attempt: Int): Live {
            publish(ConnectionState.Ssh(attempt))
            val target = try {
                SshTarget.from(profile)
            } catch (_: IllegalArgumentException) {
                throw ConnectionException(ConnectionError.InvalidProfile)
            }
            val identity = ConnectionServer(profile.id, HostEndpoint(target.host, target.port))
            val session = connectSsh(target, profile, attempt)
            try {
                publish(ConnectionState.Server(attempt))
                mapFailures { servers.resolve(session, profile) }.use { server ->
                    publish(ConnectionState.Api(attempt))
                    for ((index, password) in server.passwords.withIndex()) {
                        val client = hold(mapFailures { clients.create(server.baseUrl, password, session.socketFactory()) })
                        try {
                            val info = mapFailures { client.serverInfo() }
                            // Accepted: keep where it is stored and a keyed digest, never the password.
                            val login = server.passwordRefs.getOrNull(index)?.let { OpencodeLogin(server.baseUrl, it) }
                            val check = login?.let { PasswordCheck.of(password) }
                            return Live(session, client, info, identity, login, check)
                        } catch (failure: Throwable) {
                            release(client)
                            val refused = failure is ConnectionException &&
                                failure.error == ConnectionError.Api(ApiError.Unauthorized)
                            // The next known password may be the one this server takes.
                            if (!refused || index == server.passwords.lastIndex) throw failure
                        }
                    }
                }
                throw IllegalStateException("ResolvedServer has no password")
            } catch (failure: Throwable) {
                release(session)
                throw failure
            }
        }

        /**
         * Logs in. A server may drop a handshake while the user reads a new host key; once that key
         * is approved and stored, one more connect is made at once, which then finds the key known.
         */
        private suspend fun connectSsh(target: SshTarget, profile: ServerProfile, attempt: Int): SshSession {
            var retried = false
            while (true) {
                val verifier = Verifier(attempt, profile)
                val credential = secrets.sshCredential(profile.auth)
                try {
                    return hold(connector.connect(target, credential, verifier, options.ssh))
                } catch (rejected: SshException.HostKeyRejected) {
                    throw ConnectionException(verifier.reason ?: ConnectionError.HostKeyRejected(rejected.hostKey), rejected)
                } catch (failure: SshException) {
                    val dropped = failure is SshException.ConnectionFailed ||
                        failure is SshException.Timeout ||
                        failure is SshException.Disconnected
                    if (dropped && verifier.approvedNow && !retried) {
                        retried = true
                        continue
                    }
                    throw ConnectionException(sshError(failure), failure)
                } catch (failure: Exception) {
                    if (failure is CancellationException && stopping()) throw failure
                    throw ConnectionException(ConnectionError.Unexpected(failure), failure)
                } finally {
                    credential.wipe()
                }
            }
        }

        /** The app's host key policy: known is accepted, new asks the user, changed is refused. */
        private inner class Verifier(private val attempt: Int, private val profile: ServerProfile) : HostKeyVerifier {
            @Volatile
            var reason: ConnectionError? = null

            @Volatile
            var approvedNow = false

            override suspend fun verify(target: SshTarget, key: HostKey): HostKeyDecision {
                val endpoint = HostEndpoint(target.host, target.port)
                val server = ConnectionServer(profile.id, endpoint)
                val fingerprint = HostKeyFingerprint(key.fingerprintSha256)
                return try {
                    when (val check = knownHosts.check(endpoint, fingerprint)) {
                        HostKeyCheck.Trusted -> HostKeyDecision.Accept
                        is HostKeyCheck.Changed -> refuse(ConnectionError.HostKeyChanged(key, check.approved, server))
                        HostKeyCheck.Unknown -> ask(server, fingerprint, key)
                    }
                } catch (cancelled: CancellationException) {
                    if (stopping()) throw cancelled
                    refuse(ConnectionError.Unexpected(cancelled))
                } catch (_: IOException) {
                    refuse(ConnectionError.StorageUnavailable)
                }
            }

            private suspend fun ask(server: ConnectionServer, fingerprint: HostKeyFingerprint, key: HostKey): HostKeyDecision {
                val waiting = PendingApproval(key, CompletableDeferred())
                try {
                    return askAndStore(waiting, server, fingerprint, key)
                } finally {
                    // Whatever ended the request without storing the key, the approver learns of it.
                    waiting.stored.complete(false)
                }
            }

            private suspend fun askAndStore(
                waiting: PendingApproval,
                server: ConnectionServer,
                fingerprint: HostKeyFingerprint,
                key: HostKey,
            ): HostKeyDecision {
                pending = waiting
                val approved = try {
                    publish(ConnectionState.HostApproval(key, attempt, server)) { waiting.shown = it }
                    waiting.decision.await()
                } finally {
                    pending = null
                }
                if (!approved) return refuse(ConnectionError.HostKeyRejected(key))
                val update = try {
                    // Null: the request ended (disconnect, connect or close) before the write could
                    // start; the state already belongs to whoever ended it.
                    writeKnownHost { knownHosts.approve(server.endpoint, fingerprint) }
                        ?: return refuse(ConnectionError.HostKeyRejected(key))
                } catch (cancelled: CancellationException) {
                    if (stopping()) throw cancelled
                    return refuseApproved(waiting, ConnectionError.Unexpected(cancelled), cancelled)
                } catch (failure: IOException) {
                    return refuseApproved(waiting, ConnectionError.StorageUnavailable, failure)
                }
                return when (update) {
                    HostKeyUpdate.Stored -> {
                        approvedNow = true
                        publish(ConnectionState.Ssh(attempt))
                        waiting.stored.complete(true)
                        // A stop during the write: the key stands, but the login does not go on.
                        currentCoroutineContext().ensureActive()
                        HostKeyDecision.Accept
                    }
                    // Another key was approved meanwhile; this decision does not cover it.
                    is HostKeyUpdate.Rejected -> refuseApproved(
                        waiting,
                        update.current?.let { ConnectionError.HostKeyChanged(key, it, server) }
                            ?: ConnectionError.HostKeyRejected(key),
                    )
                }
            }

            /**
             * An approved key that was not stored ends the connect with [error]. The error is
             * published before the approver hears false, so it finds [state] telling why; the
             * connect's own end publishes the same error again, which changes nothing, its recorded
             * end included (see [publishEnd]). The end is [failure], or a trace of this step when
             * there is none, recorded with the publish.
             */
            private fun refuseApproved(
                waiting: PendingApproval,
                error: ConnectionError,
                failure: Throwable? = null,
            ): HostKeyDecision {
                publishEnd(ConnectionState.Error(error)) {
                    failure ?: ConnectionEndTrace("approved host key not stored: ${error.javaClass.simpleName}")
                }
                waiting.stored.complete(false)
                return refuse(error)
            }

            private fun refuse(error: ConnectionError): HostKeyDecision {
                reason = error
                return HostKeyDecision.Reject
            }
        }
    }

    private companion object {
        /** The server's shutdown notice, which the client passes on as [ServerEvent.Unknown]. */
        const val SHUTDOWN_EVENT = "location.shutdown"

        /**
         * What the watch asks for: one rare type that is in the spec's `types` enum for
         * `GET /api/event`, and that itself means the server is going away.
         */
        val WATCHED_EVENTS: Set<String> = setOf(SHUTDOWN_EVENT)

        fun ServerEvent.endsServer(): Boolean =
            this is ServerEvent.Disconnected || (this is ServerEvent.Unknown && rawType == SHUTDOWN_EVENT)

        /** Runs the releases of stopped connections, off their callers' threads. */
        val RELEASES = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun closeQuietly(resource: AutoCloseable) {
            try {
                resource.close()
            } catch (_: Exception) {
                // Closing is best effort; there is nothing left to do with a failure.
            }
        }

        fun sshError(failure: SshException): ConnectionError = when (failure) {
            is SshException.ConnectionFailed -> ConnectionError.Unreachable
            is SshException.Timeout -> ConnectionError.Timeout
            is SshException.Disconnected -> ConnectionError.Disconnected
            is SshException.AuthenticationFailed -> ConnectionError.AuthenticationFailed
            is SshException.HostKeyRejected -> ConnectionError.HostKeyRejected(failure.hostKey)
            is SshException.OutputLimitExceeded, is SshException.Sftp -> ConnectionError.Unexpected(failure)
        }
    }
}

/**
 * One connection of [ConnectionManager], captured by [ConnectionManager.withConnection]. Everything
 * here belongs to that connection only; none of it ever reaches a later connection or another
 * server. It hands out capabilities, never the SSH session.
 *
 * Meant for use inside the [ConnectionManager.withConnection] block.
 */
interface BoundConnection {
    /** The command runner of the connection's SSH session. */
    val exec: RemoteExec

    /** The file search of the connection's API client. */
    val fs: FsApi

    /** The server this connection reached, as [ConnectionState.Connected.server] named it. */
    val server: ConnectionServer

    /** As [ConnectionState.Connected.generation] for this connection. */
    val generation: Long

    /**
     * Where this connection's `opencode serve` is, as the SSH server reaches it, and the ref of the
     * password it accepted. Null when that is not known by ref; the default, for implementations
     * that do not reach opencode.
     */
    val opencode: OpencodeLogin? get() = null

    /**
     * Reads the password of [opencode] again from the secret store, through its ref: the
     * connection keeps no password, only a keyed digest of the accepted one, in memory. The caller
     * owns the value and closes it as soon as it is used. Null when [opencode] is null, or the
     * secret is gone, unreadable, the store unreachable, or no longer the password the server
     * accepted.
     */
    suspend fun readOpencodePassword(): SecretValue? = null

    /**
     * Opens an SFTP client confined to [root] on this connection's SSH session, as
     * [ConnectionManager.openSftp] does for the current one; the connection owns and closes it.
     *
     * @throws NotConnectedException When this connection has ended or been replaced, before or
     *   during the open. The open never moves to another connection.
     * @throws io.github.thewisekd.phopencode.core.ssh.api.SshException.Sftp When [root] is refused,
     *   missing or not readable.
     * @throws io.github.thewisekd.phopencode.core.ssh.api.SshException.Disconnected When the SSH
     *   session ends during the open.
     */
    suspend fun openSftp(root: String): SftpClient
}

/**
 * How a connection reaches `opencode serve`: [baseUrl] as seen from the SSH server (a loopback
 * address) and [passwordRef], where the password the server accepted is stored, either the
 * profile's `opencodeCredential` or [ServerResolver.startedPasswordRef]. It holds no password, and
 * prints nothing: the URL names a port of the server, and the ref names the profile.
 */
class OpencodeLogin(val baseUrl: String, val passwordRef: SecretRef) {
    override fun toString(): String = "OpencodeLogin(***)"
}
