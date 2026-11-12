package io.github.thewisekd.phopencode.feature.preview.logic.contract

/**
 * The preview page, `PreviewRoute.Page(port)` (P13.11 logic, P13.13 screen, P13.14 console,
 * P13.15 viewport): the dev server on [port] of the server's loopback, shown in an isolated
 * WebView through a [PreviewTunnel] and a [PreviewBridge].
 *
 * Rules the logic keeps:
 * - Opening asks [PreviewTunnelSource] for a tunnel to [port], opens a bridge on it with
 *   [PreviewBridgeFactory], sets [origin] and asks the screen to load [BridgeEntry.url] through
 *   [load]. A failure of either is [PageStatus.LoadFailed] with [LoadError.Failed].
 * - The tunnel's end ([PreviewTunnel.end]) moves the page to [PageStatus.ConnectionLost] without
 *   retrying; [PageIntent.Reload] then opens a new tunnel and a new bridge.
 * - The address bar takes a path on [origin] only and normalises it.
 * - [PagePresenter.decide] keeps navigation on [origin], asks before another `http(s)` address
 *   ([PageState.pendingExternal], [PageEvent.OpenExternal]) and refuses every other scheme.
 * - Closing closes the bridge and the tunnel, drops the console and resets the viewport; the
 *   status becomes [PageStatus.Closed], which tells the screen to clear the WebView's cookies,
 *   storage, cache, history and form data and destroy it.
 *
 * @property origin The bridge's origin; null until one is open.
 * @property load What the screen loads next; a new [LoadRequest.id] means load again.
 * @property addressText What the address bar shows. Prints masked.
 * @property pendingExternal An address off the origin waiting for the user's confirmation.
 */
data class PageState(
    val port: Int,
    val status: PageStatus = PageStatus.Opening,
    val origin: PreviewOrigin? = null,
    val load: LoadRequest? = null,
    val addressText: String = "/",
    val viewport: ViewportMode = ViewportMode.Mobile,
    val console: ConsoleState = ConsoleState(),
    val pendingExternal: ExternalLink? = null,
) {
    init {
        requirePort(port)
    }

    override fun toString(): String =
        "PageState(port=$port, status=$status, origin=$origin, load=$load, addressText=***, " +
            "viewport=$viewport, console=$console, pendingExternal=${if (pendingExternal == null) "null" else "***"})"
}

sealed interface PageStatus {
    data object Opening : PageStatus

    data object Loaded : PageStatus

    data class LoadFailed(val error: LoadError) : PageStatus

    /** The SSH connection dropped; nothing retries until the user reloads. */
    data object ConnectionLost : PageStatus

    /** The tunnel and the bridge are closed; the screen clears the WebView's data. */
    data object Closed : PageStatus
}

/** Why the main frame did not load. */
sealed interface LoadError {
    /** The dev server answered with an HTTP error [code]. */
    data class HttpStatus(val code: Int) : LoadError {
        init {
            require(code in 100..599) { "Not an HTTP status" }
        }
    }

    /** The WebView could not reach the bridge, or the relay broke. */
    data object Network : LoadError

    /** Nothing takes connections on the port. */
    data object NothingListens : LoadError

    /** Opening the tunnel or the bridge failed. */
    data class Failed(val reason: PreviewFailure) : LoadError
}

/**
 * Asks the screen to load [url], which may hold the bridge's secret ([BridgeEntry]); so it prints
 * masked. [id] grows with each request, so that the same address can be loaded again.
 */
class LoadRequest(val id: Long, val url: String) {
    override fun equals(other: Any?): Boolean = other is LoadRequest && other.id == id && other.url == url

    override fun hashCode(): Int = 31 * id.hashCode() + url.hashCode()

    override fun toString(): String = "LoadRequest(id=$id, url=***)"
}

/** An `http(s)` address off the preview origin. Shown in full in the confirmation; prints masked. */
data class ExternalLink(val url: String) {
    override fun toString(): String = "ExternalLink(***)"
}

/** The answer to the WebView asking whether it may follow an address. */
sealed interface NavigationDecision {
    /** On the preview origin: the WebView follows it. */
    data object Allow : NavigationDecision

    /** Another `http(s)` address: the WebView stays, the user is asked ([PageIntent.RequestExternal]). */
    data class AskExternal(val link: ExternalLink) : NavigationDecision

    /** Any other scheme or a malformed address: nothing happens, and the console says so. */
    data object Refuse : NavigationDecision
}

sealed interface PageIntent {
    /** Loads the page again; after a lost connection or a failure, opens a new tunnel and bridge. */
    data object Reload : PageIntent

    data class EditAddress(val text: String) : PageIntent {
        override fun toString(): String = "EditAddress(***)"
    }

    /** Loads the address bar's path on the origin. */
    data object GoToAddress : PageIntent

    /** The WebView started loading the main frame. */
    data object LoadStarted : PageIntent

    /** The WebView finished loading [url] in the main frame. */
    data class LoadFinished(val url: String) : PageIntent {
        override fun toString(): String = "LoadFinished(***)"
    }

    /** The main frame failed to load. */
    data class LoadFailed(val error: LoadError) : PageIntent

    /** Something for the console: a `console.*` message, a load or HTTP error, a refused request. */
    data class Report(val entry: ConsoleEntry) : PageIntent {
        override fun toString(): String = "Report(${entry.kind}, ${entry.level})"
    }

    /** Shows only [level] in the console; null shows all. */
    data class FilterConsole(val level: ConsoleLevel?) : PageIntent

    data object ClearConsole : PageIntent

    data class SetViewport(val mode: ViewportMode) : PageIntent

    /** [PagePresenter.decide] answered [NavigationDecision.AskExternal] for [url]. */
    data class RequestExternal(val url: String) : PageIntent {
        override fun toString(): String = "RequestExternal(***)"
    }

    /** The user confirmed [PageState.pendingExternal]. */
    data object ConfirmExternal : PageIntent

    data object DismissExternal : PageIntent

    data object Close : PageIntent
}

sealed interface PageEvent {
    /** Hands a confirmed address to another app; the WebView never loads it. */
    data class OpenExternal(val link: ExternalLink) : PageEvent

    data object Close : PageEvent
}

interface PagePresenter : Presenter<PageState, PageIntent, PageEvent> {
    /**
     * Whether the WebView may follow [url]. Called on the main thread from the WebView's callbacks,
     * which need the answer at once, so it never blocks, suspends or changes the state.
     */
    fun decide(url: String): NavigationDecision
}
