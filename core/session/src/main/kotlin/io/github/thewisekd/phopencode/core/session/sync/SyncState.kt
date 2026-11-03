package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [SessionSync] shows.
 *
 * @property session The session as of the last completed fetch and the events since. Null until the
 *   first fetch completes. While [phase] is not [SyncPhase.Live] it can be behind the server; it is
 *   never a partly fetched one.
 */
data class SyncState(
    val session: SessionState? = null,
    val phase: SyncPhase = SyncPhase.Connecting,
) {
    /** [SessionState] prints counts only, and [SyncPhase] carries no server text. */
    override fun toString(): String = "SyncState(session=$session, phase=$phase)"
}

/** Where [SessionSync] is between the event stream and the fetch that follows each connection. */
sealed interface SyncPhase {
    /** No connection has come up yet. */
    data object Connecting : SyncPhase

    /** Connected, and fetching the session, its messages and its pending items. */
    data object Syncing : SyncPhase

    /**
     * The last fetch failed with [error], which retrying can help; attempt [attempt] + 1 starts after
     * [delay].
     */
    data class Retrying(val error: ApiError, val attempt: Int, val delay: Duration) : SyncPhase

    /** Fetched, and following the event stream. */
    data object Live : SyncPhase

    /** The stream dropped with [reason]; the stream reconnects by itself. */
    data class Offline(val reason: ApiError) : SyncPhase

    /** Ended by [error], which retrying cannot help. [SessionSync.run] threw it. */
    data class Failed(val error: ApiError) : SyncPhase
}

/**
 * How long to wait before fetching again after a failed fetch: [initial], doubled each time, never
 * more than [max]. A new connection starts over at [initial].
 */
data class RetryDelays(
    val initial: Duration = 1.seconds,
    val max: Duration = 30.seconds,
) {
    init {
        require(initial.isPositive() && max >= initial) { "Need 0 < initial <= max" }
    }

    /** The wait after failed attempt number [attempt], counted from 1. */
    fun after(attempt: Int): Duration {
        var delay = initial
        repeat(attempt - 1) {
            if (delay >= max) return max
            delay *= 2
        }
        return minOf(delay, max)
    }
}
