package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * [SshConnector] over sshj.
 *
 * A connect runs in three timed steps with the host key check between them, untimed: TCP and the
 * handshake up to the server's key, then the rest of the handshake, then login. sshj's own waits
 * are switched off while connecting; the time limit is enforced here, by closing the socket.
 *
 * The host name is looked up first, also within the time limit. A lookup cannot be interrupted, so
 * one that outlives the limit is abandoned: its thread, from [Dispatchers.IO], stays blocked until
 * the system resolver answers, then ends. It holds no socket, and its answer is ignored.
 *
 * @param resolver For tests; the system resolver otherwise.
 * @param beforeHandOver For tests: runs with the finished session just before it is returned.
 */
class SshjConnector internal constructor(
    private val features: SessionFeatures,
    private val resolver: HostResolver = HostResolver.System,
    private val beforeHandOver: (SshSession) -> Unit = {},
) : SshConnector {
    constructor() : this(SshjSessionFeatures)

    override suspend fun connect(
        target: SshTarget,
        credential: SshCredential,
        hostKeyVerifier: HostKeyVerifier,
        options: SshConnectOptions,
    ): SshSession {
        // Set once the session exists. If it never reaches the caller, for example because the
        // caller was cancelled while the result was on its way back, it is closed here.
        val created = AtomicReference<SshSession?>()
        try {
            // On IO, timeouts measure real time even under a test dispatcher.
            return withContext(Dispatchers.IO) {
                val secret = LoginSecret.of(credential, CONFIG)
                try {
                    Attempt(target, secret, hostKeyVerifier, options, created).run().also(beforeHandOver)
                } finally {
                    secret.wipe()
                }
            }
        } catch (failure: Throwable) {
            created.getAndSet(null)?.close()
            throw failure
        }
    }

    private inner class Attempt(
        private val target: SshTarget,
        private val secret: LoginSecret,
        private val verifier: HostKeyVerifier,
        private val options: SshConnectOptions,
        private val created: AtomicReference<SshSession?>,
    ) {
        private val sockets = TrackingSocketFactory()
        private val gate = HostKeyGate()
        private val budget = Budget(options.connectTimeout)
        private val client = SSHClient(CONFIG).apply {
            socketFactory = sockets
            addHostKeyVerifier(gate)
            // Zero waits forever; Budget decides when to give up.
            transport.timeoutMs = 0
            connection.timeoutMs = 0
        }

        suspend fun run(): SshSession = coroutineScope {
            try {
                connectAndLogIn()
            } catch (failure: Throwable) {
                abort()
                throw failure
            }
        }

        private suspend fun CoroutineScope.connectAndLogIn(): SshSession {
            val address = budget.timed { lookUp(target.host) } ?: throw SshException.Timeout()
            client.connectTimeout = budget.remainingMillis()
            val handshake = blocking { client.connect(address, target.port) }

            val key = budget.timed { presentedKey(handshake) } ?: throw SshException.Timeout()

            if (decide(key) != HostKeyDecision.Accept) throw SshException.HostKeyRejected(key)
            gate.accept(key)

            budget.timed { handshake.await() }
                ?.onFailure { throw networkFailure(it) }
                ?: throw SshException.Timeout()

            val login = blocking { client.auth(target.username, secret.method) }
            budget.timed { login.await() }
                ?.onFailure { throw loginFailure(it) }
                ?: throw SshException.Timeout()

            // Later requests on this connection get a finite wait again.
            val wait = options.connectTimeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            client.transport.timeoutMs = wait
            client.connection.timeoutMs = wait
            return SshjSession(client, sockets, options, features).also(created::set)
        }

        /**
         * Runs the lookup outside this attempt's scope, so that giving up does not wait for it: a
         * blocked lookup ignores cancellation. See the class comment for what stays behind.
         */
        private suspend fun lookUp(host: String): InetAddress {
            val lookup = LOOKUPS.async { resolver.resolve(host) }
            return try {
                lookup.await()
            } catch (cancelled: CancellationException) {
                lookup.cancel()
                throw cancelled
            } catch (failure: Exception) {
                throw networkFailure(failure)
            }
        }

        /** The verifier's time is the user's, so the budget pauses. A throwing verifier rejects. */
        private suspend fun decide(key: HostKey): HostKeyDecision = try {
            verifier.verify(target, key)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            HostKeyDecision.Reject
        } catch (_: Exception) {
            HostKeyDecision.Reject
        }

        /**
         * Unblocks every sshj call in flight: the handshake thread, the reader, a login. It must be
         * `die`, not `disconnect`: `disconnect` marks the transport closed without failing the
         * key exchange or the login a thread is waiting for, and with sshj's waits switched off
         * that thread would wait forever.
         */
        private fun abort() {
            gate.reject()
            runCatching { client.transport.die(TransportException(DisconnectReason.BY_APPLICATION, "Connect abandoned")) }
            sockets.closeAll()
        }

        /** The server's key, or the failure of a handshake that ended before showing one. */
        private suspend fun presentedKey(handshake: Deferred<Result<Unit>>): HostKey =
            select {
                gate.presented.onAwait { it }
                handshake.onAwait { throw networkFailure(it.exceptionOrNull()) }
            }

        private fun CoroutineScope.blocking(block: () -> Unit): Deferred<Result<Unit>> =
            async { runCatching(block) }

        private fun networkFailure(failure: Throwable?): SshException =
            if (failure.hasInChain<SocketTimeoutException>()) {
                SshException.Timeout(RedactedFailure.of(failure))
            } else {
                SshException.ConnectionFailed(RedactedFailure.of(failure))
            }

        private fun loginFailure(failure: Throwable): SshException =
            if (failure is UserAuthException) {
                SshException.AuthenticationFailed(RedactedFailure.of(failure))
            } else {
                networkFailure(failure)
            }
    }

    /** The connect timeout, spent only while this class itself waits on the network. */
    private class Budget(private val total: Duration) {
        private var spent = Duration.ZERO

        fun remainingMillis(): Int =
            (total - spent).inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

        suspend fun <T> timed(block: suspend () -> T): T? {
            val started = TimeSource.Monotonic.markNow()
            try {
                return withTimeoutOrNull(total - spent) { block() }
            } finally {
                spent += started.elapsedNow()
            }
        }
    }

    private companion object {
        /** Building a config looks up every algorithm, so it is done once. sshj only reads it. */
        val CONFIG: Config by lazy { DefaultConfig() }

        /** Host lookups, which no connect waits for once its time is up. */
        val LOOKUPS = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ssh-host-lookup"))

        inline fun <reified T : Throwable> Throwable?.hasInChain(): Boolean =
            generateSequence(this) { it.cause }.take(16).any { it is T }
    }
}
