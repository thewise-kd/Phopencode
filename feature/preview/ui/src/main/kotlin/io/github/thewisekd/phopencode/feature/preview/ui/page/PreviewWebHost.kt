package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.webkit.WebView
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin

/**
 * What the preview's WebView callbacks reach outside the WebView: the page logic's origin, its
 * navigation policy and its intents, and the screen's progress and pull indicators. Nothing else of
 * the app: no credentials, no store, no other service.
 */
internal interface PreviewWebHost {
    /** The bridge's origin, or null while none is open. Read on the WebView's network threads. */
    val origin: PreviewOrigin?

    /** The page logic's answer for a navigation to [url]. Main thread; never blocks. */
    fun decide(url: String): NavigationDecision

    /** Hands [intent] to the page logic, on the main thread. Any thread may call it. */
    fun send(intent: PageIntent)

    /** The main frame's load progress, 0..100. Main thread. */
    fun progress(percent: Int)

    /** How far the page is pulled down past its top, 0..1; 0 when it is not. Main thread. */
    fun pulled(fraction: Float)

    /** [view]'s renderer died; [view] must be destroyed and never used again. Main thread. */
    fun renderGone(view: WebView)
}
