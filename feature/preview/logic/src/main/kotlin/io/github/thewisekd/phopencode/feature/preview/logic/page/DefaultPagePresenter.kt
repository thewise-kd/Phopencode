package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The preview page's logic (P13.11).
 *
 * A *session* is one tunnel to the route's port with one bridge on it. The first one opens once
 * the scope runs; a [PageIntent.Reload] opens a new one only when none is open (the opening failed
 * or the connection dropped), and otherwise loads the page again on the open bridge. While a
 * session is still opening, a reload is ignored.
 *
 * - A failed opening is [PageStatus.LoadFailed] with [LoadError.Failed]; a tunnel opened before a
 *   bridge failed is closed.
 * - The tunnel ending ([PreviewTunnel.end]) is [PageStatus.ConnectionLost]: the bridge and the
 *   tunnel are released, the origin is dropped and nothing retries until the user reloads, which
 *   opens a new tunnel on whatever connection then stands.
 * - The main frame's errors come from the screen ([PageIntent.LoadFailed]). The bridge reports a
 *   port nobody listens on ([BridgeProblem.NothingListens]) and answers it with 502; while the main
 *   frame loads, that problem, or a 502 after it, is [LoadError.NothingListens]. A problem at any
 *   other time (a sub-resource of a page shown) is ignored and does not reach the next main frame.
 * - Once closed ([PageIntent.Close]) the bridge and the tunnel are released, the console is dropped,
 *   the viewport reset and the status is [PageStatus.Closed], which tells the screen to clear the
 *   WebView's data; nothing opens again and every later intent is ignored.
 *
 * Nothing is logged; addresses and the bridge's secret stay in the state, which prints them masked.
 *
 * @param route The page's route, whose port the preview opens.
 * @param tunnels Opens the tunnel to the port on the active connection.
 * @param bridges Opens the bridge on the phone's loopback onto that tunnel.
 * @param scope The destination's scope; work started here stops with it, and its end releases the
 *   tunnel and the bridge.
 */
class DefaultPagePresenter(
    private val route: PreviewRoute.Page,
    private val tunnels: PreviewTunnelSource,
    private val bridges: PreviewBridgeFactory,
    private val scope: CoroutineScope,
) : PagePresenter {
    private val mutableState = MutableStateFlow(PageState(port = route.port))

    override val state: StateFlow<PageState> = mutableState.asStateFlow()

    /** Unlimited, so no event is ever dropped while nobody collects. */
    private val eventChannel = Channel<PageEvent>(Channel.UNLIMITED)

    override val events: Flow<PageEvent> = eventChannel.receiveAsFlow()

    /** Guards [mutableState]'s read-modify-write and every field below. */
    private val lock = Any()

    /** The current session; a session that is no longer current changes nothing. */
    private var session: Session? = null

    /** The current session's bridge while it and its tunnel are open. */
    private var live: PreviewBridge? = null

    /** The WebView finished a page on [live]'s origin, so it holds the secret's cookie. */
    private var entered = false

    /** The path, query and fragment of the last page finished on the origin. */
    private var currentPath = "/"

    /** The bridge reported that nothing listens during the current main-frame load. */
    private var nothingListens = false

    private var lastLoadId = 0L

    /** Read by [decide] without [lock]. */
    @Volatile private var closed = false

    /** One tunnel and the bridge on it; [release] is safe from any thread and more than once. */
    private class Session {
        var job: Job? = null

        @Volatile var tunnel: PreviewTunnel? = null

        @Volatile var bridge: PreviewBridge? = null

        fun release() {
            bridge?.let { runCatching { it.close() } }
            tunnel?.let { runCatching { it.close() } }
        }
    }

    init {
        synchronized(lock) { startSession() }
    }

    override fun onIntent(intent: PageIntent) {
        when (intent) {
            PageIntent.Reload -> reload()
            is PageIntent.EditAddress -> edit { it.copy(addressText = intent.text) }
            PageIntent.GoToAddress -> goToAddress()
            PageIntent.LoadStarted -> loadStarted()
            is PageIntent.LoadFinished -> loadFinished(intent.url)
            is PageIntent.LoadFailed -> loadFailed(intent.error)
            is PageIntent.RequestExternal -> requestExternal(intent.url)
            PageIntent.ConfirmExternal -> confirmExternal()
            PageIntent.DismissExternal -> edit { it.copy(pendingExternal = null) }
            PageIntent.Close -> close()
            // The console (P13.12) and the viewport (P13.15) are filled in by their own tasks.
            is PageIntent.Report, is PageIntent.FilterConsole, PageIntent.ClearConsole, is PageIntent.SetViewport -> Unit
        }
    }

    override fun decide(url: String): NavigationDecision =
        if (closed) NavigationDecision.Refuse else NavigationPolicy.decide(state.value.origin, url)

    // Sessions

    /**
     * Makes a new session the current one, then starts it. Called under [lock], after the state
     * shows what the session starts from: on an unconfined or immediate dispatcher the session may
     * run up to its first real suspension inside [Job.start], and it must find itself current then.
     * It starts with a `yield()`, so on a dispatched scope nothing opens before the scope runs.
     */
    private fun startSession() {
        val next = Session()
        session = next
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            run(next)
        }
        next.job = job
        job.start()
    }

    private suspend fun run(current: Session) {
        try {
            val tunnel = try {
                tunnels.open(route.port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PreviewException) {
                return failed(current, failure.failure)
            } catch (_: Exception) {
                return failed(current, PreviewFailure.Unknown)
            }
            current.tunnel = tunnel
            currentCoroutineContext().ensureActive()
            if (tunnel.end.value != null) return lost(current)

            val bridge = try {
                bridges.open(tunnel)
            } catch (failure: PreviewException) {
                return failed(current, failure.failure)
            } catch (_: Exception) {
                return failed(current, PreviewFailure.BridgeFailed)
            }
            current.bridge = bridge
            currentCoroutineContext().ensureActive()
            if (!opened(current, bridge)) return

            coroutineScope {
                val problems = launch { bridge.problems.collect { problem(current, it) } }
                tunnel.end.first { it != null }
                problems.cancel()
            }
            // Closing or reloading cancels the session before it closes the tunnel, so an end seen
            // here is the connection's.
            lost(current)
        } finally {
            current.release()
        }
    }

    private fun failed(current: Session, failure: PreviewFailure) {
        current.release()
        sessionEdit(current) {
            live = null
            it.copy(status = PageStatus.LoadFailed(LoadError.Failed(failure)), origin = null, load = null)
        }
    }

    private fun opened(current: Session, bridge: PreviewBridge): Boolean = synchronized(lock) {
        if (closed || session !== current) return false
        live = bridge
        entered = false
        nothingListens = false
        currentPath = "/"
        mutableState.value = mutableState.value.copy(
            status = PageStatus.Opening,
            origin = bridge.origin,
            load = LoadRequest(++lastLoadId, bridge.entry.url),
            addressText = "/",
        )
        true
    }

    private fun lost(current: Session) {
        current.release()
        sessionEdit(current) {
            live = null
            entered = false
            it.copy(status = PageStatus.ConnectionLost, origin = null, load = null)
        }
    }

    private fun problem(current: Session, problem: BridgeProblem) {
        if (problem != BridgeProblem.NothingListens) return
        sessionEdit(current) {
            if (live == null) return@sessionEdit it
            // Only while the main frame loads, or failed in a way this problem explains: a
            // sub-resource of a page already shown says nothing about the next main frame.
            val status = it.status
            if (status == PageStatus.Opening || status in NOTHING_LISTENS_FAILURES) {
                nothingListens = true
                it.copy(status = PageStatus.LoadFailed(LoadError.NothingListens))
            } else {
                it
            }
        }
    }

    // Intents

    private fun reload() {
        synchronized(lock) {
            if (closed) return
            val state = mutableState.value
            val bridge = live
            if (bridge != null) {
                nothingListens = false
                val reenter = !entered || state.status == PageStatus.LoadFailed(LoadError.HttpStatus(FORBIDDEN))
                val url = if (reenter) bridge.entry.url else bridge.origin.url + currentPath
                mutableState.value = state.copy(
                    status = PageStatus.Opening,
                    load = LoadRequest(++lastLoadId, url),
                    addressText = if (reenter) "/" else currentPath,
                )
                return
            }
            // No bridge and still opening: the session under way is the reload.
            if (state.status == PageStatus.Opening) return
            session?.let { old ->
                old.job?.cancel()
                old.release()
            }
            entered = false
            nothingListens = false
            mutableState.value = state.copy(status = PageStatus.Opening, origin = null, load = null)
            startSession()
        }
    }

    private fun goToAddress() {
        synchronized(lock) {
            if (closed) return
            val state = mutableState.value
            val bridge = live
            val path = AddressBar.normalize(state.addressText, bridge?.origin)
            mutableState.value = when {
                path == null -> state.copy(addressText = currentPath)
                bridge != null && entered -> {
                    currentPath = path
                    nothingListens = false
                    state.copy(
                        status = PageStatus.Opening,
                        load = LoadRequest(++lastLoadId, bridge.origin.url + path),
                        addressText = path,
                    )
                }
                else -> state.copy(addressText = path)
            }
        }
    }

    private fun loadStarted() = liveEdit {
        // A new main frame starts afresh, unless it is the one the bridge already said nobody
        // serves: the WebView may report its start after the problem arrived.
        if (it.status != PageStatus.LoadFailed(LoadError.NothingListens)) nothingListens = false
        when (it.status) {
            PageStatus.Loaded, is PageStatus.LoadFailed -> it.copy(status = PageStatus.Opening)
            else -> it
        }
    }

    private fun loadFinished(url: String) = liveEdit { state ->
        val path = NavigationPolicy.pathOn(live!!.origin, url) ?: return@liveEdit state
        entered = true
        currentPath = path
        if (state.status == PageStatus.Opening) {
            nothingListens = false
            state.copy(status = PageStatus.Loaded, addressText = path)
        } else {
            state.copy(addressText = path)
        }
    }

    private fun loadFailed(error: LoadError) = liveEdit {
        val mapped = if (error == LoadError.HttpStatus(BAD_GATEWAY) && nothingListens) LoadError.NothingListens else error
        it.copy(status = PageStatus.LoadFailed(mapped))
    }

    private fun requestExternal(url: String) {
        val decision = decide(url) as? NavigationDecision.AskExternal ?: return
        edit { it.copy(pendingExternal = decision.link) }
    }

    private fun confirmExternal() {
        val link = synchronized(lock) {
            val link = mutableState.value.pendingExternal ?: return
            mutableState.value = mutableState.value.copy(pendingExternal = null)
            link
        }
        eventChannel.trySend(PageEvent.OpenExternal(link))
    }

    private fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            live = null
            entered = false
            mutableState.value = mutableState.value.copy(
                status = PageStatus.Closed,
                origin = null,
                load = null,
                pendingExternal = null,
                console = ConsoleState(),
                viewport = ViewportMode.Mobile,
            )
            session.also { session = null }
        }
        old?.job?.cancel()
        old?.release()
        eventChannel.trySend(PageEvent.Close)
    }

    // State changes, all under [lock]

    /** Changes the state unless the page is closed. */
    private inline fun edit(change: (PageState) -> PageState) = synchronized(lock) {
        if (!closed) mutableState.value = change(mutableState.value)
    }

    /** Changes the state for [current] only while it is the open page's session. */
    private inline fun sessionEdit(current: Session, change: (PageState) -> PageState) = synchronized(lock) {
        if (!closed && session === current) mutableState.value = change(mutableState.value)
    }

    /** Changes the state only while a bridge is open: what the WebView reports after that is stale. */
    private inline fun liveEdit(change: (PageState) -> PageState) = synchronized(lock) {
        if (!closed && live != null) mutableState.value = change(mutableState.value)
    }

    private companion object {
        const val FORBIDDEN = 403
        const val BAD_GATEWAY = 502

        /** The failures of a main frame that a [BridgeProblem.NothingListens] explains. */
        val NOTHING_LISTENS_FAILURES = setOf(
            PageStatus.LoadFailed(LoadError.HttpStatus(BAD_GATEWAY)),
            PageStatus.LoadFailed(LoadError.NothingListens),
        )
    }
}
