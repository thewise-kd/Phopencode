package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.annotation.StringRes
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.ui.R

/** Reading again cannot help after these; the connection has to be made again first. */
internal val GitFailure.canRetry: Boolean
    get() = this != GitFailure.NotConnected && this != GitFailure.Unauthorized

/**
 * A plain-language explanation of [this] with what to do next. A git error whose exit code says
 * nothing to a user, and an unknown failure, take [fallback]. Never quotes git's output.
 */
@StringRes
internal fun GitFailure.messageRes(@StringRes fallback: Int): Int = when (this) {
    GitFailure.CredentialRequired -> R.string.git_remote_explain_credential
    GitFailure.NonFastForward -> R.string.git_remote_explain_non_fast_forward
    GitFailure.Diverged -> R.string.git_remote_explain_diverged
    GitFailure.WouldOverwrite -> R.string.git_remote_explain_would_overwrite
    GitFailure.Conflict -> R.string.git_remote_explain_conflict
    GitFailure.NotConnected -> R.string.git_remote_error_not_connected
    GitFailure.Unreachable -> R.string.git_remote_error_unreachable
    GitFailure.TimedOut -> R.string.git_remote_error_timed_out
    GitFailure.Unauthorized -> R.string.git_remote_error_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_remote_error_output_too_large
    GitFailure.NotRepository -> R.string.git_remote_error_not_repository
    GitFailure.GitMissing -> R.string.git_remote_error_git_missing
    GitFailure.Rejected -> R.string.git_remote_error_rejected
    GitFailure.NotFound -> R.string.git_remote_error_not_found
    is GitFailure.GitError,
    GitFailure.Unknown,
    -> fallback
}

/** What the screen says while [this] runs. */
@get:StringRes
internal val RemoteOp.runningRes: Int
    get() = when (this) {
        RemoteOp.Fetch -> R.string.git_remote_running_fetch
        RemoteOp.Pull -> R.string.git_remote_running_pull
        RemoteOp.Push -> R.string.git_remote_running_push
    }

/** The headline of a result: what happened to which operation. */
@get:StringRes
internal val RemoteResult.titleRes: Int
    get() = when (this) {
        is RemoteResult.Succeeded -> when (op) {
            RemoteOp.Fetch -> if (upToDate) R.string.git_remote_result_fetch_up_to_date else R.string.git_remote_result_fetch_done
            RemoteOp.Pull -> if (upToDate) R.string.git_remote_result_pull_up_to_date else R.string.git_remote_result_pull_done
            RemoteOp.Push -> if (upToDate) R.string.git_remote_result_push_up_to_date else R.string.git_remote_result_push_done
        }
        is RemoteResult.Cancelled -> when (op) {
            RemoteOp.Fetch -> R.string.git_remote_result_fetch_cancelled
            RemoteOp.Pull -> R.string.git_remote_result_pull_cancelled
            RemoteOp.Push -> R.string.git_remote_result_push_cancelled
        }
        is RemoteResult.Failed -> when (op) {
            RemoteOp.Fetch -> R.string.git_remote_result_fetch_failed
            RemoteOp.Pull -> R.string.git_remote_result_pull_failed
            RemoteOp.Push -> R.string.git_remote_result_push_failed
        }
    }
