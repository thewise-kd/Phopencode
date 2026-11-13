package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.ui.R

/** The key of the WebView's holder in the destination's view model store. */
private const val WEB_HOLDER_KEY = "preview_page_web"

/**
 * The live preview (P13.13): the dev server's page in an isolated WebView, loaded from the bridge on
 * the phone's loopback (D9, option (b)). Draws [state] and sends [onIntent]; [decide] answers the
 * WebView's navigations. It decides nothing itself and logs nothing: no address and no page text.
 *
 * - **WebView:** made by [PreviewWebViews] on the first [PageState.load], which is the bridge's entry
 *   URL, and loaded again on each new load request. It is kept across rotation by a view model of the
 *   destination ([PreviewWebHolder]); [PageStatus.Closed], or leaving the screen, clears its cookies,
 *   storage, cache, history and form data and destroys it, and leaving tells the logic to close, which
 *   closes the bridge.
 * - **Top bar:** back closes the preview; reload loads the page again. A determinate line shows the
 *   page's load progress.
 * - **Pull to refresh:** pulling the page down past its top, or pulling a problem panel down, reloads.
 * - **Problems:** opening, a load error, nothing listening on the port and a lost connection each
 *   take the WebView's place, with a reload action.
 * - **External address:** another `http(s)` address opens in another app only after a dialog shows
 *   it in full and the user confirms.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(
    route: PreviewRoute.Page,
    state: PageState,
    onIntent: (PageIntent) -> Unit,
    decide: (String) -> NavigationDecision,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = viewModel(key = WEB_HOLDER_KEY) { PreviewWebHolder() }
    SideEffect {
        holder.bind(state.origin, onIntent, decide)
        holder.sync(context, state)
    }
    var openFailed by rememberSaveable { mutableStateOf(false) }
    val canReload = state.status != PageStatus.Closed
    val reload = { onIntent(PageIntent.Reload) }

    PhopencodeScaffold(
        title = stringResource(R.string.preview_page_title),
        subtitle = stringResource(R.string.preview_page_port, route.port),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(PageIntent.Close) },
        actions = {
            IconButton(onClick = reload, enabled = canReload, modifier = Modifier.testTag(PageTags.RELOAD)) {
                Icon(imageVector = PhopencodeIcons.Refresh, contentDescription = stringResource(R.string.preview_page_reload))
            }
        },
    ) { padding ->
        val pull = rememberPullToRefreshState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .testTag(PageTags.CONTENT)
                .pullToRefresh(isRefreshing = false, state = pull, enabled = canReload, onRefresh = reload),
        ) {
            holder.webView?.let { web ->
                key(web) {
                    AndroidView(
                        factory = { viewContext -> holder.attach(web, viewContext) },
                        modifier = Modifier.fillMaxSize().testTag(PageTags.WEB_VIEW),
                        onRelease = holder::detach,
                    )
                }
            }
            PageOverlay(state, hasWebView = holder.webView != null, onReload = reload)
            if (holder.webView != null && holder.progress < 100 && state.status.showsPage) {
                LoadProgress(holder.progress, Modifier.align(Alignment.TopCenter))
            }
            if (openFailed) {
                ErrorBanner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm)
                        .testTag(PageTags.EXTERNAL_FAILED),
                    message = stringResource(R.string.preview_page_external_failed),
                    onDismiss = { openFailed = false },
                )
            }
            PullIndicator(pull, webPull = holder.pull, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    state.pendingExternal?.let { link ->
        ExternalConfirmation(
            link = link,
            onConfirm = {
                openFailed = !ExternalLinks.open(context, link)
                onIntent(PageIntent.ConfirmExternal)
            },
            onDismiss = { onIntent(PageIntent.DismissExternal) },
        )
    }
}

/** The WebView shows its page in these; in every other status something takes its place. */
private val PageStatus.showsPage: Boolean
    get() = this == PageStatus.Opening || this == PageStatus.Loaded

/**
 * What takes the WebView's place: the opening line while there is no page yet, or a problem with a
 * reload action. A problem panel scrolls, so that pulling it down reloads too.
 */
@Composable
private fun PageOverlay(state: PageState, hasWebView: Boolean, onReload: () -> Unit) {
    val problem = state.status.problem(state.port)
    when {
        problem != null -> Surface(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                modifier = Modifier.fillMaxSize().testTag(problem.tag),
                title = problem.title,
                message = problem.message,
                icon = if (problem.tag == PageTags.LOAD_ERROR) PhopencodeIcons.Warning else PhopencodeIcons.Server,
                action = EmptyStateAction(stringResource(R.string.preview_page_reload), onReload),
            )
        }
        state.status == PageStatus.Opening && !hasWebView -> Opening()
    }
}

/**
 * The preview is opening. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`.
 */
@Composable
private fun Opening() {
    val message = stringResource(R.string.preview_page_opening)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(PageTags.OPENING)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The page's load progress, as a determinate line under the top bar. */
@Composable
private fun LoadProgress(percent: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.preview_page_loading)
    LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = modifier
            .fillMaxWidth()
            .testTag(PageTags.PROGRESS)
            .clearAndSetSemantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
            },
    )
}

/**
 * The pull indicator: a ring that fills as a problem panel ([pull]) or the page ([webPull]) is
 * pulled down. It never spins: the reload shows as the page's load progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullIndicator(pull: PullToRefreshState, webPull: Float, modifier: Modifier = Modifier) {
    if (webPull > 0f) {
        val label = stringResource(R.string.preview_page_pulling)
        Surface(
            modifier = modifier
                .padding(top = PhopencodeTheme.spacing.lg)
                .testTag(PageTags.PULL)
                .clearAndSetSemantics { contentDescription = label },
            shape = CircleShape,
            color = PullToRefreshDefaults.indicatorContainerColor,
            shadowElevation = PullToRefreshDefaults.Elevation,
        ) {
            CircularProgressIndicator(
                progress = { webPull },
                modifier = Modifier.padding(PhopencodeTheme.spacing.sm).size(PhopencodeTheme.spacing.xl),
                color = PullToRefreshDefaults.indicatorColor,
            )
        }
    } else {
        PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = false, modifier = modifier) {
            CircularProgressIndicator(
                progress = { pull.distanceFraction.coerceIn(0f, 1f) },
                modifier = Modifier.size(PhopencodeTheme.spacing.xl),
                color = PullToRefreshDefaults.indicatorColor,
            )
        }
    }
}

/** Asks before [link] leaves the app, showing it in full. */
@Composable
private fun ExternalConfirmation(link: ExternalLink, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.preview_page_external_title),
        message = stringResource(R.string.preview_page_external_message, link.url),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(PageTags.EXTERNAL_DIALOG),
        confirmLabel = stringResource(R.string.preview_page_external_open),
    )
}
