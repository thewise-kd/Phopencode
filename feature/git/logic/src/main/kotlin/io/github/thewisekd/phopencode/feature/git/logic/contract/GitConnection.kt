package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import kotlin.coroutines.cancellation.CancellationException

/**
 * The port every git presenter runs `git` through: the command runner of the active connection. The
 * logic tasks build their command lines with `core/git` (P9.1, every argument through `ShellQuote`,
 * `GIT_TERMINAL_PROMPT=0`, stdin closed) and read the output with its parsers (P9.2); tests pass a
 * scripted `FakeRemoteExec` or a local shell through [ExecGitConnection].
 *
 * Shared rules for the git screens:
 * - `directory` of every `GitRoute` is the folder git runs in (`git -C <directory>`); a path is
 *   relative to the repository, as `git status` prints it.
 * - Nothing here or in a command a presenter builds stops or signals a process other than the one
 *   it started, uses `sudo`, resets the repository (`git reset`, `git clean`, `--hard`) or forces
 *   anything (`--force`, `-f`, `+refspec`, `--force-with-lease`). Cancelling a call ends only the
 *   command that call started, as [RemoteExec] does.
 * - Implementations never log a command line, a path or output.
 */
interface GitConnection {
    /**
     * Runs [block] with the command runner of the connection standing now. A non-zero exit of a
     * command is a result for [block] to read, not a failure of this call.
     *
     * Fails with [GitException]: [GitFailure.NotConnected] when no connection stands or it ends
     * while [block] runs, [GitFailure.of] for an SSH failure, and [GitFailure.Unknown] for anything
     * else [block] throws that is not a [GitException] already (see [toGitException]). A
     * [GitException] from [block] passes unchanged; cancellation stays a `CancellationException`.
     */
    suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T
}

/**
 * [GitConnection] on the active connection of [connection]. Each call is bound to the connection
 * that stands when it starts (`ConnectionManager.withConnection`): if that connection drops or is
 * replaced, the call is cancelled and fails as [GitFailure.NotConnected], and never moves to a later
 * connection or another server.
 */
class ConnectionGitConnection(private val connection: ConnectionManager) : GitConnection {
    override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = gitFailures {
        connection.withConnection { bound -> block(bound.exec) }
    }

    override fun toString(): String = "ConnectionGitConnection"
}

/**
 * [GitConnection] on one given command runner, with the same failures: for tests, with a scripted
 * runner or one on a local shell.
 */
class ExecGitConnection(private val exec: RemoteExec) : GitConnection {
    override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = gitFailures { block(exec) }

    override fun toString(): String = "ExecGitConnection"
}

private suspend fun <T> gitFailures(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw failure.toGitException()
}
