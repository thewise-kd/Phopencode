package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.ui.R
import java.text.NumberFormat
import java.util.Locale

private const val UNIT_STEP = 1024.0

/** The units of a size, smallest first; each is [UNIT_STEP] times the one before. */
private val SIZE_UNITS = intArrayOf(
    R.string.files_viewer_size_bytes,
    R.string.files_viewer_size_kb,
    R.string.files_viewer_size_mb,
    R.string.files_viewer_size_gb,
)

/**
 * The language the screen shows, for numbers.
 *
 * Read from the composition's configuration, so a language change redraws it; the process default is
 * not observable. An empty locale list, which Android does not give, falls back to the root locale.
 */
@Composable
internal fun viewerLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/**
 * [bytes] in the largest unit it fills, binary steps, with at most one decimal in the number format
 * of [locale]: "512 B", "1.5 KB" in English and "1,5 KB" in Turkish.
 */
@Composable
internal fun formatSize(bytes: Long): String {
    val locale = viewerLocale()
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

/** Why the file could not be read, as the end of a sentence that says it could not be opened. */
@StringRes
internal fun FilesFailure.reasonRes(): Int = when (this) {
    FilesFailure.NotConnected -> R.string.files_viewer_reason_not_connected
    FilesFailure.Unreachable -> R.string.files_viewer_reason_unreachable
    FilesFailure.Unauthorized -> R.string.files_viewer_reason_unauthorized
    FilesFailure.NotFound -> R.string.files_viewer_reason_not_found
    FilesFailure.PermissionDenied -> R.string.files_viewer_reason_permission_denied
    FilesFailure.AlreadyExists -> R.string.files_viewer_reason_already_exists
    FilesFailure.NotEmpty -> R.string.files_viewer_reason_not_empty
    FilesFailure.PathRejected -> R.string.files_viewer_reason_path_rejected
    FilesFailure.TooLarge -> R.string.files_viewer_reason_too_large
    FilesFailure.ToolMissing -> R.string.files_viewer_reason_tool_missing
    FilesFailure.Rejected -> R.string.files_viewer_reason_rejected
    FilesFailure.ServerFailed -> R.string.files_viewer_reason_server_failed
    FilesFailure.Unsupported -> R.string.files_viewer_reason_unsupported
    FilesFailure.Unknown -> R.string.files_viewer_reason_unknown
}

/** The last segment of [path], the file's name; the path itself if it has no separator. */
internal fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
