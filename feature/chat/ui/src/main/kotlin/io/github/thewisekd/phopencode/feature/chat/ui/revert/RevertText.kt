package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.ui.R
import io.github.thewisekd.phopencode.feature.chat.ui.tool.ShortenedText
import io.github.thewisekd.phopencode.feature.chat.ui.tool.shortenToolText
import java.text.NumberFormat
import java.util.Locale

/** The most lines of one file's diff the page draws. */
internal const val DIFF_MAX_LINES = 300

/** The most characters of one file's diff the page draws, for diffs with very long lines. */
internal const val DIFF_MAX_CHARS = 20_000

/** The language the page is drawn in, which sets the number format. */
@Composable
internal fun revertLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** A count with the language's grouping, such as "1,234" in English and "1.234" in Turkish. */
internal fun formatCount(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/** The lines all files add, or null when no file gave its count. */
internal fun totalAdditions(files: List<FileDiff>): Long? =
    files.mapNotNull { it.file.additions }.takeIf { it.isNotEmpty() }?.sum()

/** The lines all files remove, or null when no file gave its count. */
internal fun totalDeletions(files: List<FileDiff>): Long? =
    files.mapNotNull { it.file.deletions }.takeIf { it.isNotEmpty() }?.sum()

/** Lines added and removed, such as "+12 −4", either alone when the other is unknown, or null for neither. */
@Composable
internal fun countsText(additions: Long?, deletions: Long?, locale: Locale): String? {
    val added = additions?.let { stringResource(R.string.chat_revert_additions, formatCount(it, locale)) }
    val removed = deletions?.let { stringResource(R.string.chat_revert_deletions, formatCount(it, locale)) }
    return when {
        added != null && removed != null -> stringResource(R.string.chat_revert_counts, added, removed)
        else -> added ?: removed
    }
}

/** A file's status in words; one the app does not know is shown as the server names it. */
@Composable
internal fun statusText(status: VcsFileStatus): String = when (status) {
    VcsFileStatus.Added -> stringResource(R.string.chat_revert_status_added)
    VcsFileStatus.Modified -> stringResource(R.string.chat_revert_status_modified)
    VcsFileStatus.Deleted -> stringResource(R.string.chat_revert_status_deleted)
    VcsFileStatus.Renamed -> stringResource(R.string.chat_revert_status_renamed)
    VcsFileStatus.Untracked -> stringResource(R.string.chat_revert_status_untracked)
    is VcsFileStatus.Unknown -> status.raw
}

/** Why a load or step failed, as a sentence. */
@StringRes
internal fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_revert_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_revert_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_revert_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_revert_reason_not_found
    ChatFailure.Conflict -> R.string.chat_revert_reason_conflict
    ChatFailure.Rejected -> R.string.chat_revert_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_revert_reason_server
    ChatFailure.Unknown -> R.string.chat_revert_reason_unknown
}

/** What failed, taking the reason as its argument. */
@StringRes
internal fun RevertAction.errorRes(): Int = when (this) {
    RevertAction.Stage -> R.string.chat_revert_error_stage
    RevertAction.Commit -> R.string.chat_revert_error_commit
    RevertAction.Cancel -> R.string.chat_revert_error_cancel
}

/** The step on its way, in words. */
@StringRes
internal fun RevertAction.pendingRes(): Int = when (this) {
    RevertAction.Stage -> R.string.chat_revert_pending_stage
    RevertAction.Commit -> R.string.chat_revert_pending_commit
    RevertAction.Cancel -> R.string.chat_revert_pending_cancel
}

/** The start of [patch], at most [DIFF_MAX_LINES] lines and [DIFF_MAX_CHARS] characters. */
internal fun shortenDiff(patch: String): ShortenedText = shortenToolText(patch, DIFF_MAX_LINES, DIFF_MAX_CHARS)

/** What a cut diff leaves out, such as "200 more lines"; null when nothing was cut. */
@Composable
internal fun moreText(shown: ShortenedText, locale: Locale): String? = when {
    !shown.truncated -> null
    shown.hiddenLines > 0 ->
        pluralStringResource(R.plurals.chat_revert_more_lines, shown.hiddenLines, formatCount(shown.hiddenLines.toLong(), locale))
    else ->
        pluralStringResource(R.plurals.chat_revert_more_characters, shown.hiddenChars, formatCount(shown.hiddenChars.toLong(), locale))
}

/** The colors of a diff's lines; added and removed lines also keep their `+` and `-`, so color only repeats them. */
@Immutable
internal data class DiffColors(val added: Color, val removed: Color, val hunk: Color, val header: Color)

@Composable
internal fun diffColors(): DiffColors {
    val tones = PhopencodeTheme.status
    return DiffColors(
        added = tones.success.color,
        removed = tones.danger.color,
        hunk = MaterialTheme.colorScheme.primary,
        header = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** [text] as a unified diff: file headers, hunk lines, added and removed lines each in their color. */
internal fun highlightDiff(text: String, colors: DiffColors): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val color = when {
            line.startsWith("+++") || line.startsWith("---") -> colors.header
            line.startsWith("@@") -> colors.hunk
            line.startsWith("+") -> colors.added
            line.startsWith("-") -> colors.removed
            else -> null
        }
        if (color == null) append(line) else withStyle(SpanStyle(color = color)) { append(line) }
    }
}
