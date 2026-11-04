package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerDetector
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.start.ServerPassword
import io.github.thewisekd.phopencode.core.remote.start.ServerStartException
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.ServerStarter
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.IOException

/** Server discovery and startup on one session; [Default] is [ServerDetector] and [ServerStarter]. */
interface ServerTools {
    suspend fun findServer(session: SshSession): ServerLookup

    suspend fun findBinary(session: SshSession): BinaryLookup

    suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer

    companion object Default : ServerTools {
        override suspend fun findServer(session: SshSession): ServerLookup =
            ServerDetector.forSession(session).findServer()

        override suspend fun findBinary(session: SshSession): BinaryLookup =
            ServerDetector.forSession(session).findBinary()

        override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
            ServerStarter(session.exec).start(password, options)
    }
}

/**
 * Where to reach `opencode serve` and with which password. [close] wipes the password.
 *
 * @property baseUrl As seen from the SSH server, always a loopback address.
 * @property passwords The candidates to try in order; never empty.
 */
class ResolvedServer(val baseUrl: String, val passwords: List<SecretValue>) : AutoCloseable {
    init {
        require(passwords.isNotEmpty()) { "No password" }
    }

    override fun close() = passwords.forEach(SecretValue::close)

    override fun toString(): String = "ResolvedServer(***)"
}

/**
 * Finds the `opencode serve` to use on a session, starting one when none runs.
 *
 * - A running server gets every known password: the profile's `opencodeCredential` and the one
 *   this resolver stored when it started a server for the profile, in the order [knownPasswords]
 *   gives. The caller tries them in turn until the server accepts one.
 * - When none runs, the opencode binary is looked up and started with a new random password. The
 *   password is stored under [startedPasswordRef] before the start, so that a server which comes
 *   up can always be reached again, even if the start itself then reports a failure.
 *
 * Failures are thrown as [ConnectionException]; SSH failures pass through as they are.
 */
class ServerResolver(
    private val secrets: SecretStore,
    private val tools: ServerTools = ServerTools,
    private val startedPasswordRef: (ServerProfile) -> SecretRef = Companion::startedPasswordRef,
) {
    suspend fun resolve(session: SshSession, profile: ServerProfile): ResolvedServer =
        when (val lookup = tools.findServer(session)) {
            is ServerLookup.Running -> ResolvedServer(baseUrl(lookup.host, lookup.port), knownPasswords(profile, lookup.source))
            is ServerLookup.NotRunning -> start(session, profile)
            is ServerLookup.Failed -> throw ConnectionException(ConnectionError.ServerDetectionFailed(lookup.reason))
        }

    /**
     * Every password known for a running server, likeliest first. A server the state file confirms
     * is one this app started, so the password stored at that start comes first; for any other the
     * profile's credential does. Fails when neither is known.
     */
    private suspend fun knownPasswords(profile: ServerProfile, source: ServerSource): List<SecretValue> {
        val found = ArrayList<SecretValue>(2)
        try {
            val refs = listOfNotNull(profile.opencodeCredential, startedPasswordRef(profile))
            val ordered = if (source == ServerSource.StateFile) refs.asReversed() else refs
            for (ref in ordered.distinct()) secrets.readOrNull(ref)?.let(found::add)
        } catch (failure: Throwable) {
            found.forEach(SecretValue::close)
            throw failure
        }
        if (found.isEmpty()) throw ConnectionException(ConnectionError.ServerPasswordUnknown)
        return found
    }

    private suspend fun start(session: SshSession, profile: ServerProfile): ResolvedServer {
        val binary = when (val lookup = tools.findBinary(session)) {
            is BinaryLookup.Found -> lookup.path
            BinaryLookup.Missing -> throw ConnectionException(ConnectionError.OpencodeMissing)
            is BinaryLookup.Failed -> throw ConnectionException(ConnectionError.ServerDetectionFailed(lookup.reason))
        }
        val password = ServerPassword.generate()
        try {
            try {
                secrets.put(startedPasswordRef(profile), password)
            } catch (_: IOException) {
                throw ConnectionException(ConnectionError.StorageUnavailable)
            }
            val started = try {
                tools.start(session, password, ServerStartOptions(binary = binary))
            } catch (failure: ServerStartException) {
                throw ConnectionException(ConnectionError.ServerStartFailed(ServerStartFailure.of(failure)))
            }
            return ResolvedServer(baseUrl(LOOPBACK_V4, started.port), listOf(password))
        } catch (failure: Throwable) {
            password.close()
            throw failure
        }
    }

    companion object {
        private const val LOOPBACK_V4 = "127.0.0.1"

        /** Where the password of a server started for [profile] is kept. */
        fun startedPasswordRef(profile: ServerProfile): SecretRef = SecretRef("opencode-started-server:${profile.id.value}")

        internal fun baseUrl(host: String, port: Int): String =
            if (':' in host) "http://[$host]:$port" else "http://$host:$port"
    }
}
