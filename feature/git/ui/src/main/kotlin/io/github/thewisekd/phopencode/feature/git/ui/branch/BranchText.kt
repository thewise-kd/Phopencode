package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.ui.R

/**
 * Something is being read or run. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`.
 */
@Composable
internal fun StillProgress(@StringRes text: Int, tag: String, modifier: Modifier = Modifier) {
    val message = stringResource(text)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Listing again cannot help after these; the connection has to be made again first. */
internal val GitFailure.canRetry: Boolean
    get() = this != GitFailure.NotConnected && this != GitFailure.Unauthorized

/**
 * The failures listing, creating, switching and deleting branches can meet have their own words;
 * the others, which belong to pushes and pulls, and a git error whose exit code says nothing to a
 * user, take [fallback].
 */
@StringRes
internal fun GitFailure.messageRes(@StringRes fallback: Int): Int = when (this) {
    GitFailure.NotConnected -> R.string.git_branch_error_not_connected
    GitFailure.Unreachable -> R.string.git_branch_error_unreachable
    GitFailure.TimedOut -> R.string.git_branch_error_timed_out
    GitFailure.Unauthorized -> R.string.git_branch_error_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_branch_error_output_too_large
    GitFailure.NotRepository -> R.string.git_branch_error_not_repository
    GitFailure.GitMissing -> R.string.git_branch_error_git_missing
    GitFailure.Rejected -> R.string.git_branch_error_rejected
    GitFailure.NotFound -> R.string.git_branch_error_not_found
    GitFailure.WouldOverwrite -> R.string.git_branch_error_would_overwrite
    GitFailure.Conflict -> R.string.git_branch_error_conflict
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    is GitFailure.GitError,
    GitFailure.Unknown,
    -> fallback
}

/** What the screen says while the operation runs. */
@get:StringRes
internal val BranchOp.busyRes: Int
    get() = when (this) {
        BranchOp.Create -> R.string.git_branch_busy_create
        BranchOp.Switch -> R.string.git_branch_busy_switch
        BranchOp.Delete -> R.string.git_branch_busy_delete
    }

/** What the screen says when the operation failed in a way it has no own words for. */
@get:StringRes
internal val BranchOp.failureRes: Int
    get() = when (this) {
        BranchOp.Create -> R.string.git_branch_error_create
        BranchOp.Switch -> R.string.git_branch_error_switch
        BranchOp.Delete -> R.string.git_branch_error_delete
    }

@get:StringRes
internal val BranchNameProblem.messageRes: Int
    get() = when (this) {
        BranchNameProblem.Blank -> R.string.git_branch_name_blank
        BranchNameProblem.Invalid -> R.string.git_branch_name_invalid
        BranchNameProblem.Taken -> R.string.git_branch_name_taken
    }
