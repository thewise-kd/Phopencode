package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PageCursor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * What the fakes of one client share: whether it is closed, and how a test steers its calls.
 *
 * Every call of every fake API goes through here, which is what gives them one rule set: a call
 * returns or throws [ApiException], and after [close] it throws [ApiError.Closed] even if it was
 * already running.
 */
class FakeApiControl {
    private data class State(val closed: Boolean = false, val stalled: Boolean = false, val running: Int = 0)

    private val state = MutableStateFlow(State())
    private val failures = ArrayDeque<ApiError>()
    private val callNames = mutableListOf<String>()
    private val closeActions = mutableListOf<() -> Unit>()

    /** The calls that reached the server, oldest first, as `sessions.list`. */
    val calls: List<String>
        get() = synchronized(callNames) { callNames.toList() }

    val isClosed: Boolean
        get() = state.value.closed

    /** The next [times] calls fail with [error], after any failures already waiting. */
    fun failNext(error: ApiError, times: Int = 1) {
        synchronized(failures) { repeat(times) { failures.addLast(error) } }
    }

    /** Calls made from now on stay running until [resume] or [close]. */
    fun stall() {
        state.update { it.copy(stalled = true) }
    }

    fun resume() {
        state.update { it.copy(stalled = false) }
    }

    /** Waits until at least [count] calls are running. */
    suspend fun awaitRunningCalls(count: Int) {
        state.first { it.running >= count }
    }

    /** Ends the event stream and fails all running and later calls. Safe to call again. */
    fun close() {
        val first = state.getAndUpdateClosed()
        if (first) synchronized(closeActions) { closeActions.toList() }.forEach { it() }
    }

    private fun MutableStateFlow<State>.getAndUpdateClosed(): Boolean {
        var first = false
        update { current ->
            first = !current.closed
            current.copy(closed = true)
        }
        return first
    }

    internal fun onClose(action: () -> Unit) {
        synchronized(closeActions) { closeActions += action }
    }

    internal fun checkOpen() {
        if (isClosed) throw ApiException(ApiError.Closed)
    }

    internal suspend fun <T> call(name: String, block: () -> T): T {
        currentCoroutineContext().ensureActive()
        checkOpen()
        synchronized(callNames) { callNames += name }
        state.update { it.copy(running = it.running + 1) }
        try {
            state.first { it.closed || !it.stalled }
            checkOpen()
            synchronized(failures) { failures.removeFirstOrNull() }?.let { throw ApiException(it) }
            return block()
        } finally {
            state.update { it.copy(running = it.running - 1) }
        }
    }
}

internal fun checkLimit(limit: Int?) {
    if (limit != null && limit <= 0) throw ApiException(ApiError.BadRequest("limit must be positive"))
}

/** The default page size. The server's own is unknown, and no caller may rely on it. */
private const val DEFAULT_PAGE_SIZE = 50
private const val CURSOR_PREFIX = "fake-cursor-"

/**
 * Pages like the server: `next` is there on the last page too, so that a caller which treats a
 * cursor as "more to come" asks once more and gets an empty page.
 */
internal fun <T> List<T>.page(limit: Int?, cursor: PageCursor?): Page<T> {
    checkLimit(limit)
    val start = cursor?.let {
        it.value.takeIf { value -> value.startsWith(CURSOR_PREFIX) }
            ?.removePrefix(CURSOR_PREFIX)
            ?.toIntOrNull()
            ?.takeIf { offset -> offset >= 0 }
            ?: throw ApiException(ApiError.BadRequest("unknown cursor"))
    } ?: 0
    val size = limit ?: DEFAULT_PAGE_SIZE
    val items = drop(start).take(size)
    return Page(
        items = items,
        previousCursor = if (start > 0) PageCursor("$CURSOR_PREFIX${(start - size).coerceAtLeast(0)}") else null,
        nextCursor = PageCursor("$CURSOR_PREFIX${start + items.size}"),
    )
}
