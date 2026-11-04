package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * Resources: everything a connection opens (the SSH session, with its processes and tunnels, and
 * the API client with the manager's event stream) belongs to it, and is closed when the
 * connection drops or fails, and when [connect] replaces it. Login secrets are wiped right after
 * use.
 *
 * Closing: [disconnect] and [close] suspend until every connection this manager started has
 * finished, its cleanup included: credentials wiped, client and session closed. Concurrent calls
 * all wait for that same moment. Cancelling their caller does not cut the wait short. Cancelling
 * [scope] closes the manager too, whatever it is doing, idle included; the state turns
 * [ConnectionState.Closed] at once and the connections finish their cleanup on their own.
 *
 * Nothing is logged, and no state or error carries an address or a secret.
 *
 * @param scope Runs the connections; it must have a [Job]. Cancelling it closes the manager.
 */
class ConnectionManager(
    private val connector: SshConnector,
    private val secrets: SecretStore,
    private val knownHosts: KnownHostsStore,
    private val servers: ServerResolver,
    private val clients: OpencodeClientFactory,
    private val scope: CoroutineScope,
    private val options: ConnectionOptions = ConnectionOptions(),
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** Guarded by [lock]: the connection [state] speaks for. */
    private var current: Run? = null

    /** Guarded by [lock]: every connection whose job has not completed, [current] included. */
    private val runs = LinkedHashSet<Run>()

    /** Guarded by [lock]. */
    private var closed = false
    private var generation = 0L

    /** Completes once the manager is closed and every run has completed. */
    private val fullyClosed = CompletableDeferred<Unit>()

    /** Null until registered; the handler may already have run by then. */
    @Volatile
    private var scopeWatch: DisposableHandle? = null

    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    init {
        val scopeJob = requireNotNull(scope.coroutineContext[Job]) { "The scope has no Job" }
        // Runs immediately for a scope that already ended.
        scopeWatch = scopeJob.invokeOnCompletion { closeNow() }
    }

    /** Connects to [profile], ending any current connection first. Does nothing once closed. */
    fun connect(profile: ServerProfile) {
        val run: Run
        val previous = synchronized(lock) {
            if (closed) return
            // The job exists before anyone can see this run, so a stop at any moment cancels it.
            run = Run(profile)
            // A scope that ended just now cancels the job at once, which closes the manager.
            if (closed) return
            val previous = current
            current = run
            mutableState.value = ConnectionState.Ssh()
            previous
        }
        previous?.stop()
        run.start()
    }

    /**
     * Approves [key] for the host waiting in [ConnectionState.HostApproval] and stores it as known.
     * False when no approval waits for this very key.
     */
    fun approveHostKey(key: HostKey): Boolean = decide(key, approved = true)

    /** Rejects [key]; the connection ends with [ConnectionError.HostKeyRejected]. False as for [approveHostKey]. */
    fun rejectHostKey(key: HostKey): Boolean = decide(key, approved = false)

    private fun decide(key: HostKey, approved: Boolean): Boolean {
        val pending = synchronized(lock) { current?.pending } ?: return false
        return pending.key == key && pending.decision.complete(approved)
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
     * Ends the connection and returns to [ConnectionState.Idle] once every connection has finished
     * its cleanup. After [close], it waits for the close to finish instead.
     */
    suspend fun disconnect() {
        val stopping = synchronized(lock) {
            if (closed) null else {
                mutableState.value = ConnectionState.Idle
                current = null
                runs.toList()
            }
        }
        if (stopping == null) {
            awaitClosed()
            return
        }
        stopping.forEach(Run::stop)
        withContext(NonCancellable) { stopping.forEach { it.join() } }
    }

    /**
     * Ends the connection for good. Returns once every connection has finished its cleanup, also
     * when another close or a cancelled scope started the close. Safe to call again.
     */
    suspend fun close() {
        closeNow()
        awaitClosed()
    }

    private suspend fun awaitClosed() {
        withContext(NonCancellable) { fullyClosed.await() }
    }

    /**
     * Marks the manager closed and stops every run, without waiting; [fullyClosed] completes when
     * the last run has completed. Never suspends, so a scope's completion handler can call it.
     */
    private fun closeNow() {
        val stopping = synchronized(lock) {
            if (closed) return
            closed = true
            mutableState.value = ConnectionState.Closed
            current = null
            if (runs.isEmpty()) fullyClosed.complete(Unit)
            runs.toList()
        }
        scopeWatch?.dispose()
        stopping.forEach(Run::stop)
    }

    /** Called once per run, when its job has completed: cleanup has run by then. */
    private fun completed(run: Run) {
        synchronized(lock) {
            runs.remove(run)
            if (closed && runs.isEmpty()) fullyClosed.complete(Unit)
        }
    }

    private class PendingApproval(val key: HostKey, val decision: CompletableDeferred<Boolean>)

    private class Live(val session: SshSession, val client: OpencodeClient, val info: ServerInfo)

    /** Why a connection that was published as connected ended, and whether its event stream opened. */
    private class Drop(val cause: ConnectionError, val stood: Boolean)

    /** One [connect]: its job, what it holds open, and its waiting approval. */
    private inner class Run(profile: ServerProfile) {
        private val held = ArrayList<AutoCloseable>()
        private var stopped = false

        @Volatile
        var pending: PendingApproval? = null

        val kicks = Channel<Unit>(Channel.CONFLATED)

        /** Lazy, so that a [stop] before [start] means it never runs. */
        private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                execute(profile)
            } finally {
                releaseAll()
            }
        }

        init {
            synchronized(lock) { runs += this }
            job.invokeOnCompletion { cause ->
                if (cause != null) ended(cause)
                completed(this)
            }
        }

        suspend fun join() = job.join()

        /**
         * The job ended by something other than a normal return. A stop is expected; a cancellation
         * otherwise comes from the scope, so the manager closes; any other failure is an error.
         * Resources are already released by then.
         */
        private fun ended(cause: Throwable) {
            if (synchronized(held) { stopped }) return
            if (cause is CancellationException) {
                closeNow()
            } else {
                publish(ConnectionState.Error(ConnectionError.Unexpected))
            }
        }

        /** Does nothing once [stop] has cancelled the job. */
        fun start() {
            job.start()
        }

        /** Publishes [state] unless a newer [connect], [disconnect] or [close] took over. */
        fun publish(state: ConnectionState) {
            synchronized(lock) { if (current === this) mutableState.value = state }
        }

        /** Takes [resource] into this run; one arriving after [stop] is closed at once. */
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
            synchronized(held) {
                held.asReversed().forEach(::closeQuietly)
                held.clear()
            }
        }

        fun stop() {
            synchronized(held) { stopped = true }
            pending?.decision?.complete(false)
            job.cancel()
            releaseAll()
        }

        private suspend fun execute(profile: ServerProfile) {
            val policy = options.reconnect
            var attempt = 0
            var everConnected = false
            while (true) {
                val error = try {
                    val live = establish(profile, attempt)
                    everConnected = true
                    // A request made before this connection stood is about an older one. Dropped
                    // before the state is published, so a request answering that state counts.
                    while (kicks.tryReceive().isSuccess) Unit
                    publish(ConnectionState.Connected(live.client, live.info, nextGeneration()))
                    val dropped = awaitDrop(live)
                    release(live.client)
                    release(live.session)
                    // Only a connection that really stood starts the attempts over. One whose event
                    // stream never opened counts as a failed attempt, or a server that answers its
                    // info call but nothing else would be retried forever.
                    if (dropped.stood) attempt = 0
                    dropped.cause
                } catch (failure: ConnectionException) {
                    failure.error
                }
                if (!everConnected || !error.retryable) {
                    publish(ConnectionState.Error(error))
                    return
                }
                attempt++
                val limit = policy.maxAttempts
                if (limit != null && attempt > limit) {
                    publish(ConnectionState.Error(error))
                    return
                }
                val wait = policy.delayBefore(attempt)
                publish(ConnectionState.Reconnecting(attempt, wait, error))
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
        private suspend fun awaitDrop(live: Live): Drop = coroutineScope {
            val stood = AtomicBoolean(false)
            val sshDropped = async { live.session.connected.first { !it } }
            val serverDropped = async { watchServer(live.client) { stood.set(true) } }
            val cause = select<ConnectionError> {
                sshDropped.onAwait { ConnectionError.Disconnected }
                serverDropped.onAwait { it }
                kicks.onReceive { ConnectionError.Disconnected }
            }
            sshDropped.cancel()
            serverDropped.cancel()
            // The server's stream breaks with the SSH connection that carries it; that is an SSH drop.
            Drop(if (!live.session.connected.value) ConnectionError.Disconnected else cause, stood.get())
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
         * instead. A failure, the stream failing to open included, ends as that API error. Only
         * the connection is dropped: nothing on the server is stopped.
         *
         * @param opened Called on each [ServerEvent.Connected], that is once the stream is open.
         */
        private suspend fun watchServer(client: OpencodeClient, opened: () -> Unit): ConnectionError = try {
            client.events.events(WATCHED_EVENTS)
                .onEach { if (it is ServerEvent.Connected) opened() }
                .takeWhile { !it.endsServer() }
                .collect { }
            ConnectionError.ServerLost
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiException) {
            ConnectionError.Api(failure.error)
        } catch (_: Exception) {
            ConnectionError.Unexpected
        }

        private suspend fun establish(profile: ServerProfile, attempt: Int): Live {
            publish(ConnectionState.Ssh(attempt))
            val target = try {
                SshTarget.from(profile)
            } catch (_: IllegalArgumentException) {
                throw ConnectionException(ConnectionError.InvalidProfile)
            }
            val session = connectSsh(target, profile, attempt)
            try {
                publish(ConnectionState.Server(attempt))
                mapFailures { servers.resolve(session, profile) }.use { server ->
                    publish(ConnectionState.Api(attempt))
                    for ((index, password) in server.passwords.withIndex()) {
                        val client = hold(mapFailures { clients.create(server.baseUrl, password, session.socketFactory()) })
                        try {
                            return Live(session, client, mapFailures { client.serverInfo() })
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
                val verifier = Verifier(attempt)
                val credential = secrets.sshCredential(profile.auth)
                try {
                    return hold(connector.connect(target, credential, verifier, options.ssh))
                } catch (rejected: SshException.HostKeyRejected) {
                    throw ConnectionException(verifier.reason ?: ConnectionError.HostKeyRejected(rejected.hostKey))
                } catch (failure: SshException) {
                    val dropped = failure is SshException.ConnectionFailed ||
                        failure is SshException.Timeout ||
                        failure is SshException.Disconnected
                    if (dropped && verifier.approvedNow && !retried) {
                        retried = true
                        continue
                    }
                    throw ConnectionException(sshError(failure))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw ConnectionException(ConnectionError.Unexpected)
                } finally {
                    credential.wipe()
                }
            }
        }

        /** The app's host key policy: known is accepted, new asks the user, changed is refused. */
        private inner class Verifier(private val attempt: Int) : HostKeyVerifier {
            @Volatile
            var reason: ConnectionError? = null

            @Volatile
            var approvedNow = false

            override suspend fun verify(target: SshTarget, key: HostKey): HostKeyDecision {
                val endpoint = HostEndpoint(target.host, target.port)
                val fingerprint = HostKeyFingerprint(key.fingerprintSha256)
                return try {
                    when (val check = knownHosts.check(endpoint, fingerprint)) {
                        HostKeyCheck.Trusted -> HostKeyDecision.Accept
                        is HostKeyCheck.Changed -> refuse(ConnectionError.HostKeyChanged(key, check.approved))
                        HostKeyCheck.Unknown -> ask(endpoint, fingerprint, key)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    refuse(ConnectionError.StorageUnavailable)
                }
            }

            private suspend fun ask(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint, key: HostKey): HostKeyDecision {
                val waiting = PendingApproval(key, CompletableDeferred())
                pending = waiting
                val approved = try {
                    publish(ConnectionState.HostApproval(key, attempt))
                    waiting.decision.await()
                } finally {
                    pending = null
                }
                if (!approved) return refuse(ConnectionError.HostKeyRejected(key))
                return when (val update = knownHosts.approve(endpoint, fingerprint)) {
                    HostKeyUpdate.Stored -> {
                        approvedNow = true
                        publish(ConnectionState.Ssh(attempt))
                        HostKeyDecision.Accept
                    }
                    // Another key was approved meanwhile; this decision does not cover it.
                    is HostKeyUpdate.Rejected -> refuse(
                        update.current?.let { ConnectionError.HostKeyChanged(key, it) }
                            ?: ConnectionError.HostKeyRejected(key),
                    )
                }
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
            is SshException.OutputLimitExceeded, is SshException.Sftp -> ConnectionError.Unexpected
        }

        /** Turns every failure of a server or API step into [ConnectionException]; cancellation passes. */
        suspend fun <T> mapFailures(block: suspend () -> T): T = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ConnectionException) {
            throw failure
        } catch (failure: SshException) {
            throw ConnectionException(sshError(failure))
        } catch (failure: ApiException) {
            throw ConnectionException(ConnectionError.Api(failure.error))
        } catch (_: Exception) {
            throw ConnectionException(ConnectionError.Unexpected)
        }
    }
}
