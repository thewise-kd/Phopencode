package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * Something loads. A still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests, which open these screens while the
 * presenter loads, would never return. TalkBack hears the same as for `Loading`: the message, an
 * indeterminate progress and a polite live region.
 */
@Composable
internal fun StillProgress(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Reading again cannot fix a lost connection or refused credentials. */
internal val CatalogFailure.canRetry: Boolean
    get() = when (this) {
        CatalogFailure.NotConnected, CatalogFailure.Unauthorized -> false
        CatalogFailure.Unreachable, CatalogFailure.NotFound, CatalogFailure.Rejected,
        CatalogFailure.ServerFailed, CatalogFailure.Unknown,
        -> true
    }

/** What the list says about a failure. */
@get:StringRes
internal val CatalogFailure.listMessageRes: Int
    get() = when (this) {
        CatalogFailure.NotConnected -> R.string.catalog_skills_error_not_connected
        CatalogFailure.Unreachable -> R.string.catalog_skills_error_unreachable
        CatalogFailure.Unauthorized -> R.string.catalog_skills_error_unauthorized
        CatalogFailure.NotFound -> R.string.catalog_skills_error_not_found
        CatalogFailure.Rejected -> R.string.catalog_skills_error_rejected
        CatalogFailure.ServerFailed -> R.string.catalog_skills_error_server
        CatalogFailure.Unknown -> R.string.catalog_skills_error_unknown
    }

/** What a skill's details say about a failure: the list's words, but a missing skill is gone. */
@get:StringRes
internal val CatalogFailure.detailMessageRes: Int
    get() = when (this) {
        CatalogFailure.NotFound -> R.string.catalog_skills_detail_error_not_found
        else -> listMessageRes
    }
