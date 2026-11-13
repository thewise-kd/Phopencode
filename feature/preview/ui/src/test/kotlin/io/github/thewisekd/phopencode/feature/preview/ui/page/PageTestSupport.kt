package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.webkit.WebView
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
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

/**
 * Stands in for the system WebView's document start scripts: [supported] says whether it has them,
 * [add] records each registration, as `script:<view>` in [events] too, or throws [failure].
 */
internal class FakeDocumentStartScripts(
    private val events: MutableList<String> = mutableListOf(),
    override val supported: Boolean = true,
    private val failure: RuntimeException? = null,
) : DocumentStartScripts {
    /** One registration: the WebView, the script and its origin rules. */
    data class Added(val view: WebView, val script: String, val originRules: Set<String>)

    val added = mutableListOf<Added>()

    override fun add(view: WebView, script: String, originRules: Set<String>) {
        failure?.let { throw it }
        added += Added(view, script, originRules)
        events += "script:$view"
    }
}

/** A page that asks for its first load: without a WebView that can block WebRTC, nothing loads. */
internal val loading = PageState(
    port = PAGE_PORT,
    status = PageStatus.Opening,
    load = LoadRequest(1, "http://127.0.0.1:41234/"),
)

/** Draws the page with [state] on a system WebView that has no document start scripts. */
internal fun ComposeContentTestRule.setPageScreenOnOldWebView(state: PageState) {
    val scripts = FakeDocumentStartScripts(supported = false)
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                PageScreenContent(
                    route = pageRoute,
                    state = state,
                    onIntent = {},
                    decide = { NavigationDecision.Refuse },
                    modifier = Modifier,
                    newHolder = {
                        PreviewWebHolder { context, host -> PreviewWebViews.create(context, host, scripts = scripts) }
                    },
                )
            }
        }
    }
}
