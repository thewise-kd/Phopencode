package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.ui.R

/** What the page shows in place of the WebView when it has nothing to show, and its test tag. */
internal data class PageProblem(val tag: String, val title: String, val message: String)

/**
 * The problem [this] status means, if any, for the preview of [port]. A connection that dropped and
 * a port nothing listens on read the same whether the tunnel failed to open or the page failed to
 * load; every other failure to open reads as a plain "did not open", with why where it helps.
 */
@Composable
internal fun PageStatus.problem(port: Int): PageProblem? = when (this) {
    PageStatus.Opening, PageStatus.Loaded, PageStatus.Closed -> null
    PageStatus.ConnectionLost -> connectionLost()
    is PageStatus.LoadFailed -> when (val cause = error) {
        LoadError.NothingListens -> nothingListens(port)
        LoadError.Network -> loadFailed(stringResource(R.string.preview_page_load_failed_network))
        is LoadError.HttpStatus -> loadFailed(stringResource(R.string.preview_page_load_failed_http, cause.code))
        is LoadError.Failed -> cause.reason.openProblem(port)
    }
}

@Composable
private fun PreviewFailure.openProblem(port: Int): PageProblem = when (this) {
    PreviewFailure.ConnectionLost -> connectionLost()
    PreviewFailure.NothingListens -> nothingListens(port)
    PreviewFailure.NotConnected -> PageProblem(
        PageTags.LOAD_ERROR,
        stringResource(R.string.preview_page_not_connected_title),
        stringResource(R.string.preview_page_not_connected_message),
    )
    PreviewFailure.OpencodePort -> PageProblem(
        PageTags.LOAD_ERROR,
        stringResource(R.string.preview_page_opencode_title),
        stringResource(R.string.preview_page_opencode_message),
    )
    PreviewFailure.BridgeFailed -> PageProblem(
        PageTags.LOAD_ERROR,
        stringResource(R.string.preview_page_start_failed_title),
        stringResource(R.string.preview_page_bridge_failed_message),
    )
    PreviewFailure.TimedOut,
    PreviewFailure.OutputTooLarge,
    PreviewFailure.CommandFailed,
    PreviewFailure.Unknown,
    -> PageProblem(
        PageTags.LOAD_ERROR,
        stringResource(R.string.preview_page_start_failed_title),
        stringResource(R.string.preview_page_start_failed_message),
    )
}

@Composable
private fun connectionLost() = PageProblem(
    PageTags.CONNECTION_LOST,
    stringResource(R.string.preview_page_connection_lost_title),
    stringResource(R.string.preview_page_connection_lost_message),
)

@Composable
private fun nothingListens(port: Int) = PageProblem(
    PageTags.NOTHING_LISTENS,
    stringResource(R.string.preview_page_nothing_listens_title, port),
    stringResource(R.string.preview_page_nothing_listens_message),
)

@Composable
private fun loadFailed(message: String) =
    PageProblem(PageTags.LOAD_ERROR, stringResource(R.string.preview_page_load_failed_title), message)
