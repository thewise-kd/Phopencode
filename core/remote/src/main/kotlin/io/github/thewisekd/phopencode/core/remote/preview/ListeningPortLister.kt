package io.github.thewisekd.phopencode.core.remote.preview

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A TCP port that takes connections on the server's loopback.
 *
 * @property isOpencode The opencode server listens here; a preview must not open it.
 */
data class ListeningPort(val port: Int, val isOpencode: Boolean)

/** What listing the server's ports gave. No case carries a host name, a path or command output. */
sealed interface PortListing {
    /** @property ports Ascending, each port once. */
    data class Listed(val ports: List<ListeningPort>) : PortListing

    /**
     * The server has neither `ss` nor readable `/proc/net/tcp` tables, or the tables' byte order
     * could not be determined.
     */
    data object Unsupported : PortListing

    data class Failed(val reason: PortListFailure) : PortListing
}

enum class PortListFailure {
    TimedOut,

    /** The command printed more than the lister reads. */
    OutputTooLarge,

    /** The command ran and failed. */
    CommandFailed,

    /** The SSH connection ended or failed. */
    ConnectionLost,
}

/**
 * Lists the server's listening TCP ports that are reachable on its loopback, read-only, through
 * [exec] alone. It runs `ss -ltnH` and, when that does not work, reads `/proc/net/tcp` and
 * `/proc/net/tcp6`. Both commands are fixed; nothing here starts, stops or signals a process or
 * uses `sudo`.
 *
 * The two sources differ in one case: a wildcard socket bound to a device other than `lo`
 * (`SO_BINDTODEVICE`). `ss` shows the device and the port is dropped; the `/proc` tables do not, so
 * the fallback lists it. Opening such a port fails safely, because the preview tunnel reaches only
 * loopback, and the preview reports that nothing listens on it.
 *
 * @param timeout Limit for each command.
 * @param maxOutputBytes Limit for each command's stdout and stderr.
 */
class ListeningPortLister(
    private val exec: RemoteExec,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(timeout.isPositive() && timeout.isFinite()) { "Timeout must be positive" }
        require(maxOutputBytes > 0) { "Output limit must be positive" }
    }

    /** @param opencodePort The opencode server's port, marked in the result. */
    suspend fun list(opencodePort: Int? = null): PortListing {
        require(opencodePort == null || opencodePort in 1..MAX_PORT) { "Port is out of range" }
        val ss = when (val outcome = run(PortListCommands.SS)) {
            is Outcome.Failed -> return PortListing.Failed(outcome.reason)
            is Outcome.Done -> outcome.result
        }
        if (ss.succeeded) return listed(ListenTable.fromSs(ss.stdout.decodeToString()), opencodePort)

        // `ss` is missing, too old for `-H` or failing: the kernel's tables say the same, except
        // for device binding, which they do not show (see the class comment).
        val proc = when (val outcome = run(PortListCommands.PROC_NET)) {
            is Outcome.Failed -> return PortListing.Failed(outcome.reason)
            is Outcome.Done -> outcome.result
        }
        return when {
            // Without the server's byte order no address can be read, so nothing is guessed.
            proc.succeeded -> ListenTable.fromProcNet(proc.stdout.decodeToString())
                ?.let { listed(it, opencodePort) } ?: PortListing.Unsupported
            proc.exitCode == PortListCommands.NO_TABLES -> PortListing.Unsupported
            else -> PortListing.Failed(PortListFailure.CommandFailed)
        }
    }

    private fun listed(ports: Set<Int>, opencodePort: Int?): PortListing.Listed =
        PortListing.Listed(ports.sorted().map { ListeningPort(it, isOpencode = it == opencodePort) })

    /** Runs one fixed command with stdin closed; every SSH failure becomes an outcome. */
    private suspend fun run(command: String): Outcome = try {
        Outcome.Done(exec.run(ExecRequest(command, stdin = null, timeout = timeout), maxOutputBytes))
    } catch (_: SshException.Timeout) {
        Outcome.Failed(PortListFailure.TimedOut)
    } catch (_: SshException.OutputLimitExceeded) {
        Outcome.Failed(PortListFailure.OutputTooLarge)
    } catch (_: SshException) {
        Outcome.Failed(PortListFailure.ConnectionLost)
    }

    private sealed interface Outcome {
        class Done(val result: ExecResult) : Outcome

        class Failed(val reason: PortListFailure) : Outcome
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 15.seconds

        /** Far above what `ss` prints for a busy development machine. */
        const val DEFAULT_MAX_OUTPUT_BYTES: Long = 1024L * 1024

        private const val MAX_PORT = 65535
    }
}
