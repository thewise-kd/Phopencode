package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects the app to one server: SSH, host key approval, `opencode serve` discovery or startup,
 * and the API through the SSH tunnel; then watches the connection and reconnects after a drop.
 * [state] tells where it is; see [ConnectionState].
 *
 * Reconnecting: when an SSH connection that stood ends, the manager releases its client and
 * session and tries again after [ReconnectPolicy]'s delays. Only [ConnectionError.retryable]
 * failures are tried again, up to [ReconnectPolicy.maxAttempts]; any other failure, and every
 * failure of a first connect, ends in [ConnectionState.Error], since those need the user.
 *
 * Resources: everything a connection opens (the SSH session, with its processes and tunnels, and
 * the API client) belongs to it, and is closed when the connection drops or fails, and when
 * [connect] replaces it. Login secrets are wiped right after use.
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
     * failed event stream or a network change. Does nothing while idle, stopped or connecting.
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
                    awaitDrop(live.session)
                    release(live.client)
                    release(live.session)
                    attempt = 0
                    ConnectionError.Disconnected
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

        /** Returns when the session ends or [reconnect] is called. */
        private suspend fun awaitDrop(session: SshSession) {
            coroutineScope {
                val dropped = async { session.connected.first { !it } }
                select<Unit> {
                    dropped.onAwait { }
                    kicks.onReceive { }
                }
                dropped.cancel()
            }
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
