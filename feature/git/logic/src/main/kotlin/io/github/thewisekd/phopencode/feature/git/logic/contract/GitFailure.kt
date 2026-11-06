package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * Why a git screen could not do what it was asked, as few cases as the screens word differently.
 * Every git screen runs `git` on the server of the active connection through [GitConnection], whose
 * calls fail with [GitException].
 *
 * Carries no server text, no path and no branch name: printing one is safe.
 */
sealed interface GitFailure {
    /** No connection stands, or the one in use ended. Going back to the connection fixes it. */
    data object NotConnected : GitFailure

    /** The server could not be reached. Retrying may help. */
    data object Unreachable : GitFailure

    /** The command or the connection took longer than its limit. Retrying may help. */
    data object TimedOut : GitFailure

    /** The server refused the login or its host key. */
    data object Unauthorized : GitFailure

    /** The command printed more than the phone reads; nothing of it is shown. */
    data object OutputTooLarge : GitFailure

    /** The folder is not inside a git repository. */
    data object NotRepository : GitFailure

    /** `git` is not installed on the server, or not on the command shell's `PATH`. */
    data object GitMissing : GitFailure

    /**
     * The input was refused before anything ran: a path that leaves the repository, a malformed
     * commit hash or branch name, a control character.
     */
    data object Rejected : GitFailure

    /** The commit, branch, remote or path is not there, for example gone since the list was read. */
    data object NotFound : GitFailure

    /**
     * The remote asked for a user name, a password or a passphrase. Commands run with
     * `GIT_TERMINAL_PROMPT=0` and stdin closed, so the prompt fails at once instead of hanging; the
     * fix is a credential helper or an SSH key on the server.
     */
    data object CredentialRequired : GitFailure

    /** A push was refused because the remote has commits this branch lacks. Pulling first fixes it. */
    data object NonFastForward : GitFailure

    /** A `--ff-only` pull is impossible: both sides have new commits. A merge or rebase is needed. */
    data object Diverged : GitFailure

    /** Local changes would be overwritten (by a pull or a branch switch); git changed nothing. */
    data object WouldOverwrite : GitFailure

    /** The repository has unresolved merge conflicts. */
    data object Conflict : GitFailure

    /**
     * `git` ran and failed in a way none of the cases above describes.
     *
     * @property exitCode Null when the process died from a signal or the server reported nothing.
     */
    data class GitError(val exitCode: Int?) : GitFailure

    data object Unknown : GitFailure

    companion object {
        fun of(error: SshException): GitFailure = when (error) {
            is SshException.ConnectionFailed -> Unreachable
            is SshException.Timeout -> TimedOut
            is SshException.Disconnected -> NotConnected
            is SshException.AuthenticationFailed, is SshException.HostKeyRejected -> Unauthorized
            is SshException.OutputLimitExceeded -> OutputTooLarge
            is SshException.Sftp -> Unknown
        }
    }
}

/**
 * How [GitConnection] fails. The message is fixed text naming [failure] only, so that no path or
 * server text reaches a log through it. Cancellation is never wrapped: it stays a
 * `CancellationException`.
 *
 * @param cause The original error; it must not be one whose message quotes a path or a secret.
 */
class GitException(val failure: GitFailure, cause: Throwable? = null) :
    Exception("Git operation failed: $failure", cause)

/**
 * A failure reaching a git presenter, as a [GitException]: one already is passes unchanged, a lost
 * connection is [GitFailure.NotConnected], an SSH failure maps with [GitFailure.of], anything else
 * is [GitFailure.Unknown]. No cause is kept: an error deep in a chain may quote a path.
 *
 * Never call it on a `CancellationException`; rethrow that.
 */
fun Exception.toGitException(): GitException = when (this) {
    is GitException -> this
    is NotConnectedException -> GitException(GitFailure.NotConnected)
    is SshException -> GitException(GitFailure.of(this))
    else -> GitException(GitFailure.Unknown)
}
