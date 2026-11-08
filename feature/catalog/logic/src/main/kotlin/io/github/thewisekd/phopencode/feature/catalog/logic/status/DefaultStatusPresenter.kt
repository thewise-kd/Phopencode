package io.github.thewisekd.phopencode.feature.catalog.logic.status

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The MCP servers' connection status and the plugin list (P10.6), view only: the only server calls
 * are `CatalogApi.mcpServers` and `CatalogApi.plugins`, with the server's default folder
 * (`location = null`); nothing connects, disconnects, enables or authorizes anything.
 *
 * One structure, driven by the current generation, the latest value of
 * `CatalogConnection.generations`, collected in [scope] after a `yield()`:
 * - Each value `g` cancels every read running and empties both lists. For a connection, both lists
 *   are read for `g`; for none, both show [CatalogFailure.NotConnected] without asking anything.
 * - [StatusIntent.Refresh] reads both lists again for the current generation, keeping what they show
 *   while it reads (it belongs to that generation) but not their old error; a read still running is
 *   replaced. Before the first value, or without a connection, there is nothing to read.
 * - The two lists are read apart: one that fails keeps the other.
 * - A read's answer, items or error, is applied only if, under one lock, it is that list's newest
 *   read and its call ran on the current generation. Anything else is dropped. A failed read keeps
 *   the items shown, which are of that same generation.
 * - A call that finds another connection than the one it was started for asks nothing and empties
 *   its list; the report of that connection reads it.
 * - A call that could not run, without a connection, is dropped: the current generation has a read
 *   of its own, or will have one, or is none and already shows NotConnected.
 *
 * Nothing is kept between presenters, since a status changes on the server. Failures become a
 * [CatalogFailure] and carry no server text. Nothing here logs; the MCP and plugin errors stay on
 * their rows and out of `toString`.
 *
 * @param connection The catalog of the active connection, read fresh each time.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultStatusPresenter(
    private val connection: CatalogConnection,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState())

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    private val eventChannel = Channel<StatusEvent>(Channel.BUFFERED)

    override val events: Flow<StatusEvent> = eventChannel.receiveAsFlow()

    /** Guards [reported], [current] and every reader's bookkeeping. */
    private val lock = Any()

    /** Guarded by [lock]: whether [connection]'s generations have reported anything yet. */
    private var reported = false

    /** Guarded by [lock]: the current generation; null for no connection. */
    private var current: Long? = null

    private val mcp = Reader(
        fetch = { it.mcpServers(location = null) },
        get = StatusState::mcp,
        set = { state, list -> state.copy(mcp = list) },
    )

    private val plugins = Reader(
        fetch = { it.plugins(location = null) },
        get = StatusState::plugins,
        set = { state, list -> state.copy(plugins = list) },
    )

    init {
        scope.launch {
            yield()
            connection.generations.collect(::onGeneration)
        }
    }

    override fun onIntent(intent: StatusIntent) {
        when (intent) {
            StatusIntent.Refresh -> synchronized(lock) {
                val generation = current
                if (reported && generation != null) {
                    mcp.read(generation, clear = false)
                    plugins.read(generation, clear = false)
                }
            }
            StatusIntent.Close -> eventChannel.trySend(StatusEvent.Close)
        }
    }

    private fun onGeneration(generation: Long?) {
        synchronized(lock) {
            reported = true
            current = generation
            if (generation == null) {
                mcp.notConnected()
                plugins.notConnected()
            } else {
                mcp.read(generation, clear = true)
                plugins.read(generation, clear = true)
            }
        }
    }

    /** Reads one list and keeps its slot of [state]. Its fields are guarded by [lock]. */
    private inner class Reader<T>(
        private val fetch: suspend (CatalogApi) -> List<T>,
        private val get: (StatusState) -> CatalogList<T>,
        private val set: (StatusState, CatalogList<T>) -> StatusState,
    ) {
        private var job: Job? = null

        /** How many reads were started or stopped; only the newest one's answer counts. */
        private var started = 0L

        /** Under [lock]: replaces any read running with one for [generation]. */
        fun read(generation: Long, clear: Boolean) {
            job?.cancel()
            val ticket = ++started
            mutableState.update {
                set(it, CatalogList(loading = true, items = if (clear) emptyList() else get(it).items))
            }
            job = scope.launch { load(ticket, generation) }
        }

        /** Under [lock]: stops any read running and shows that no connection stands. */
        fun notConnected() {
            job?.cancel()
            job = null
            ++started
            mutableState.update { set(it, CatalogList(loading = false, error = CatalogFailure.NotConnected)) }
        }

        private suspend fun load(ticket: Long, generation: Long) {
            var ranOn: Long? = null
            var items: List<T>? = null
            var failure: CatalogFailure? = null
            try {
                items = connection.call { catalog, on ->
                    ranOn = on
                    if (on == generation) fetch(catalog) else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                failure = CatalogFailure.of(e.error)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Not an ApiException, against CatalogConnection's word; its message may quote the server.
                failure = CatalogFailure.Unknown
            }
            synchronized(lock) {
                if (ticket != started) return
                val on = ranOn ?: return // Could not run: dropped, see the class comment.
                when {
                    on != generation -> mutableState.update { set(it, CatalogList(loading = true)) }
                    on != current -> Unit
                    failure != null -> mutableState.update {
                        set(it, CatalogList(loading = false, items = get(it).items, error = failure))
                    }
                    else -> mutableState.update { set(it, CatalogList(loading = false, items = items.orEmpty())) }
                }
            }
        }
    }

    override fun toString(): String = "DefaultStatusPresenter"
}
