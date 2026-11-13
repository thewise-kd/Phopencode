package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure

/** A made-up dev server port. */
internal const val PAGE_PORT = 5173

internal val pageRoute = PreviewRoute.Page(PAGE_PORT)

/** An address off the preview, long enough to need its full text checked. */
internal const val EXTERNAL_URL = "https://docs.example.invalid/guide/getting-started?ref=preview&lang=en#install"

/**
 * States without a [PageState.load], so no WebView is made: the Robolectric tests check what the
 * screen draws around it. The WebView itself is checked on a device (androidTest).
 */
internal val opening = PageState(port = PAGE_PORT)
internal val connectionLost = PageState(port = PAGE_PORT, status = PageStatus.ConnectionLost)
internal val nothingListens = PageState(port = PAGE_PORT, status = PageStatus.LoadFailed(LoadError.NothingListens))
internal val httpError = PageState(port = PAGE_PORT, status = PageStatus.LoadFailed(LoadError.HttpStatus(502)))
internal val networkError = PageState(port = PAGE_PORT, status = PageStatus.LoadFailed(LoadError.Network))
internal val closed = PageState(port = PAGE_PORT, status = PageStatus.Closed)
internal val askingExternal = PageState(port = PAGE_PORT, status = PageStatus.Loaded, pendingExternal = ExternalLink(EXTERNAL_URL))

internal fun failedToOpen(reason: PreviewFailure) =
    PageState(port = PAGE_PORT, status = PageStatus.LoadFailed(LoadError.Failed(reason)))

/** Draws the page with [state]; [onIntent] gets what it sends. */
internal fun ComposeContentTestRule.setPageScreen(
    state: PageState,
    decide: (String) -> NavigationDecision = { NavigationDecision.Refuse },
    onIntent: (PageIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { PageScreen(route = pageRoute, state = state, onIntent = onIntent, decide = decide) }
        }
    }
}

/** Shows one state after another in a single composition, as a test may set its content only once. */
internal class PageStates(
    private val compose: ComposeContentTestRule,
    private val onIntent: (PageIntent) -> Unit = {},
) {
    private val shown = mutableStateOf<PageState?>(null)
    private var started = false

    fun show(state: PageState) {
        shown.value = state
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface {
                        shown.value?.let {
                            PageScreen(route = pageRoute, state = it, onIntent = onIntent, decide = { NavigationDecision.Refuse })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
