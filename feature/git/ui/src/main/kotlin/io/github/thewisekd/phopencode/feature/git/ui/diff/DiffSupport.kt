package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import io.github.thewisekd.phopencode.feature.git.ui.R
import java.text.NumberFormat
import java.util.Locale

/**
 * One sideways offset shared by every line row, so that all lines scroll together.
 *
 * A `ScrollState` cannot be shared: each `horizontalScroll` sets its maximum to its own row's
 * overflow, and a short row would pull the offset back to 0. Here the maximum is the widest line
 * seen, less the width the text gets: [seed] is the longest line by characters, measured up front,
 * and every row that is laid out reports its own width, which covers wide characters the seed
 * missed.
 */
@Stable
internal class SideScroll(seed: Int) {
    private var viewport by mutableIntStateOf(0)
    private var content by mutableIntStateOf(seed)
    private var raw by mutableFloatStateOf(0f)

    /** How far the lines can move, in pixels. */
    val max: Int
        get() = (content - viewport).coerceAtLeast(0)

    /** How far the lines have moved left, in pixels; never past [max]. */
    val offset: Float
        get() = raw.coerceIn(0f, max.toFloat())

    /** Moves the lines by [delta] pixels, positive toward their ends; returns how far they moved. */
    fun scrollBy(delta: Float): Float {
        val before = offset
        raw = (before + delta).coerceIn(0f, max.toFloat())
        return raw - before
    }

    /** A row was laid out with [viewport] pixels for its text, which is [content] pixels wide. */
    fun measured(viewport: Int, content: Int) {
        Snapshot.withoutReadObservation {
            if (this.viewport != viewport) this.viewport = viewport
            if (content > this.content) this.content = content
        }
    }
}

/** A [SideScroll] for [diff], kept while the diff stays the same. */
@Composable
internal fun rememberSideScroll(diff: GitFileDiff, hunks: List<DiffHunk>, code: TextStyle): SideScroll {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val endPadding = PhopencodeTheme.spacing.sm
    return remember(diff, code, density) {
        val longest = hunks.asSequence().flatMap { it.lines.asSequence() }.maxByOrNull { it.text.length }
        val seed = longest?.let {
            measurer.measure(it.text.replace("\t", "    "), code, softWrap = false, maxLines = 1).size.width +
                with(density) { endPadding.roundToPx() }
        } ?: 0
        SideScroll(seed)
    }
}

/** Why the diff could not be read, as the end of a sentence that says so. */
@Composable
internal fun failureReason(failure: GitFailure): String = when (failure) {
    is GitFailure.GitError -> failure.exitCode
        ?.let { stringResource(R.string.git_diff_reason_git_error, it) }
        ?: stringResource(R.string.git_diff_reason_git_error_no_code)
    else -> stringResource(failure.reasonRes())
}

@StringRes
private fun GitFailure.reasonRes(): Int = when (this) {
    GitFailure.NotConnected -> R.string.git_diff_reason_not_connected
    GitFailure.Unreachable -> R.string.git_diff_reason_unreachable
    GitFailure.TimedOut -> R.string.git_diff_reason_timed_out
    GitFailure.Unauthorized -> R.string.git_diff_reason_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_diff_reason_output_too_large
    GitFailure.NotRepository -> R.string.git_diff_reason_not_repository
    GitFailure.GitMissing -> R.string.git_diff_reason_git_missing
    GitFailure.Rejected -> R.string.git_diff_reason_rejected
    GitFailure.NotFound -> R.string.git_diff_reason_not_found
    GitFailure.CredentialRequired -> R.string.git_diff_reason_credential_required
    GitFailure.NonFastForward -> R.string.git_diff_reason_non_fast_forward
    GitFailure.Diverged -> R.string.git_diff_reason_diverged
    GitFailure.WouldOverwrite -> R.string.git_diff_reason_would_overwrite
    GitFailure.Conflict -> R.string.git_diff_reason_conflict
    is GitFailure.GitError -> R.string.git_diff_reason_git_error_no_code
    GitFailure.Unknown -> R.string.git_diff_reason_unknown
}

@StringRes
internal fun GitChangeKind.labelRes(): Int = when (this) {
    GitChangeKind.Added -> R.string.git_diff_kind_added
    GitChangeKind.Modified -> R.string.git_diff_kind_modified
    GitChangeKind.Deleted -> R.string.git_diff_kind_deleted
    GitChangeKind.Renamed -> R.string.git_diff_kind_renamed
    GitChangeKind.Copied -> R.string.git_diff_kind_copied
    GitChangeKind.TypeChanged -> R.string.git_diff_kind_type_changed
    GitChangeKind.Untracked -> R.string.git_diff_kind_untracked
    GitChangeKind.Unmerged -> R.string.git_diff_kind_unmerged
}

private const val UNIT_STEP = 1024.0

/** The units of a size, smallest first; each is [UNIT_STEP] times the one before. */
private val SIZE_UNITS = intArrayOf(
    R.string.git_diff_size_bytes,
    R.string.git_diff_size_kb,
    R.string.git_diff_size_mb,
    R.string.git_diff_size_gb,
)

/**
 * [bytes] in the largest unit it fills, binary steps, with at most one decimal in the number format
 * of the screen's language: "1.5 MB" in English and "1,5 MB" in Turkish.
 */
@Composable
internal fun formatSize(bytes: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= UNIT_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= UNIT_STEP
        unit++
    }
    val number = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (unit == 0) 0 else 1
        minimumFractionDigits = 0
    }
    return stringResource(SIZE_UNITS[unit], number.format(value))
}

/** The last segment of [path], the file's name; the path itself if it has no separator. */
internal fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
