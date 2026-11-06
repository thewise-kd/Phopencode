package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.SHORT_HASH_LENGTH
import io.github.thewisekd.phopencode.feature.git.ui.R
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

/** Between the parts of a line; not words, so the same in every language. */
internal const val SEPARATOR = " · "

private const val PATH_SEPARATOR = '/'

internal fun shortHash(hash: String): String = hash.take(SHORT_HASH_LENGTH)

/** The last part of a repository path, a file row's headline. */
internal fun fileName(path: String): String = path.trimEnd(PATH_SEPARATOR).substringAfterLast(PATH_SEPARATOR)

/** The folder of a repository path, or null for a path at the repository's root. */
internal fun folderOf(path: String): String? {
    val trimmed = path.trimEnd(PATH_SEPARATOR)
    val cut = trimmed.lastIndexOf(PATH_SEPARATOR)
    return if (cut > 0) trimmed.substring(0, cut) else null
}

/**
 * [instant] as a date, with the time of day when [withTime], in the screen's language and the
 * phone's time zone. A fixed date rather than "3 days ago": a relative date would have to tick.
 */
@Composable
internal fun formatDate(instant: Instant, withTime: Boolean = false): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale, withTime) {
        val format = if (withTime) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        } else {
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        }
        format.format(Date(instant.toEpochMilliseconds()))
    }
}

/**
 * Something is being read. A still line rather than a spinner: an endless animation keeps
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
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Reading again cannot help after these; the connection has to be made again first. */
internal val GitFailure.canRetry: Boolean
    get() = this != GitFailure.NotConnected && this != GitFailure.Unauthorized

/**
 * The failures reading history can meet have their own words; the others, which belong to pushes,
 * pulls and switches, and a git error whose exit code says nothing to a user, take [fallback].
 */
@StringRes
internal fun GitFailure.messageRes(@StringRes fallback: Int): Int = when (this) {
    GitFailure.NotConnected -> R.string.git_log_error_not_connected
    GitFailure.Unreachable -> R.string.git_log_error_unreachable
    GitFailure.TimedOut -> R.string.git_log_error_timed_out
    GitFailure.Unauthorized -> R.string.git_log_error_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_log_error_output_too_large
    GitFailure.NotRepository -> R.string.git_log_error_not_repository
    GitFailure.GitMissing -> R.string.git_log_error_git_missing
    GitFailure.Rejected -> R.string.git_log_error_rejected
    GitFailure.NotFound -> R.string.git_log_error_not_found
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    GitFailure.Conflict,
    is GitFailure.GitError,
    GitFailure.Unknown,
    -> fallback
}

@get:StringRes
internal val GitChangeKind.labelRes: Int
    get() = when (this) {
        GitChangeKind.Added -> R.string.git_log_kind_added
        GitChangeKind.Modified -> R.string.git_log_kind_modified
        GitChangeKind.Deleted -> R.string.git_log_kind_deleted
        GitChangeKind.Renamed -> R.string.git_log_kind_renamed
        GitChangeKind.Copied -> R.string.git_log_kind_copied
        GitChangeKind.TypeChanged -> R.string.git_log_kind_type_changed
        GitChangeKind.Untracked -> R.string.git_log_kind_untracked
        GitChangeKind.Unmerged -> R.string.git_log_kind_unmerged
    }
