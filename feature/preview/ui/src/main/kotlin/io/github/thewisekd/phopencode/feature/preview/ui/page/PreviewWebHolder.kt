package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import io.github.thewisekd.phopencode.feature.preview.ui.page.viewport.ViewportSettings

private const val LOADED = 100

/**
 * Keeps the preview's WebView for as long as the page's destination is on the back stack, so a
 * rotation keeps the page, its history and its storage, and nothing longer.
 *
 * - The WebView is made on the first [PageState.load] and loads each new [io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest.id] once.
 * - [PageStatus.Closed], or the destination leaving the back stack ([onCleared]), clears the
 *   WebView's data and destroys it ([release]). Leaving also tells the page logic to close, which
 *   closes the bridge and the tunnel; that is a no-op if it closed already.
 * - A dead renderer destroys the WebView the same way and reports a network failure. The request it
 *   was loading is not replayed: only the next new load request makes a new WebView.
 *
 * The WebView's context is a [MutableContextWrapper] around the activity while it is on screen and
 * around the application in between ([attach], [detach]), so the kept WebView never holds an old
 * activity.
 *
 * Main thread, but for [send] and [origin], which the WebView's network thread uses.
 *
 * @param create Makes the WebView; the instrumented tests serve pages from memory through it.
 */
internal class PreviewWebHolder(
    private val create: (Context, PreviewWebHost) -> PreviewWebView = { context, host ->
        PreviewWebViews.create(context, host)
    },
) : ViewModel(), PreviewWebHost {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    override var origin: PreviewOrigin? = null
        private set

    private var decider: (String) -> NavigationDecision = { NavigationDecision.Refuse }
    private var sink: (PageIntent) -> Unit = {}
    private var loadedId: Long? = null

    /** The WebView, once a load was asked for; null before, after [release] and after its renderer died. */
    var webView: PreviewWebView? by mutableStateOf(null)
        private set

    /** The main frame's load progress, 0..100. */
    var progress: Int by mutableIntStateOf(LOADED)
        private set

    /** How far the page is pulled down, 0..1. */
    var pull: Float by mutableFloatStateOf(0f)
        private set

    /** Takes the page logic's latest origin, intents and navigation policy. */
    fun bind(origin: PreviewOrigin?, onIntent: (PageIntent) -> Unit, decide: (String) -> NavigationDecision) {
        this.origin = origin
        sink = onIntent
        decider = decide
    }

    /** Brings the WebView in line with [state]: makes it, loads a new request, or releases it. */
    fun sync(context: Context, state: PageState) {
        if (state.status == PageStatus.Closed) {
            release()
            return
        }
        val load = state.load ?: return
        if (load.id == loadedId) return
        loadedId = load.id
        val view = webView ?: create(MutableContextWrapper(context), this).also { webView = it }
        progress = 0
        // A user agent takes effect on the next load only, so the mode is set before each one.
        ViewportSettings.apply(view.settings, state.viewport, context)
        view.loadUrl(load.url)
    }

    /** [view], ready to go on screen in [context]: off any old parent, on [context]. */
    fun attach(view: View, context: Context): View {
        (view.parent as? ViewGroup)?.removeView(view)
        (view.context as? MutableContextWrapper)?.baseContext = context
        return view
    }

    /** [view] left the screen: off its parent, and on the application, so no activity is kept. */
    fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        (view.context as? MutableContextWrapper)?.let { it.baseContext = it.baseContext.applicationContext }
    }

    /** Clears the WebView's data and destroys it, and forgets the last load. Idempotent. */
    fun release() {
        loadedId = null
        destroyWebView()
    }

    /**
     * Clears the WebView's data and destroys it, but keeps the last load's id, so that only a new
     * load request makes a new WebView.
     */
    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        progress = LOADED
        pull = 0f
        PreviewWebViews.clearAndDestroy(view)
        detach(view)
    }

    override fun onCleared() {
        release()
        sink(PageIntent.Close)
        sink = {}
        decider = { NavigationDecision.Refuse }
        origin = null
    }

    override fun decide(url: String): NavigationDecision = decider(url)

    override fun send(intent: PageIntent) {
        if (Looper.myLooper() == Looper.getMainLooper()) sink(intent) else main.post { sink(intent) }
    }

    override fun progress(percent: Int) {
        progress = percent.coerceIn(0, LOADED)
    }

    override fun pulled(fraction: Float) {
        pull = fraction
    }

    override fun renderGone(view: WebView) {
        if (view !== webView) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return
        }
        // The last load stays remembered: replaying it on a new WebView by itself could loop on a
        // page that keeps killing its renderer. The user's reload brings a new load request.
        destroyWebView()
        sink(PageIntent.LoadFailed(LoadError.Network))
    }
}
