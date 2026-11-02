package io.github.thewisekd.phopencode.core.api

import kotlinx.coroutines.flow.Flow

/** The server's live event stream. */
interface EventApi {
    /**
     * The events of all sessions, child sessions included. The flow is cold: each collector gets a
     * connection of its own, and cancelling the collection closes it.
     *
     * The flow reconnects by itself, with a growing delay, and does not complete. Each drop emits
     * [ServerEvent.Disconnected] and each new connection [ServerEvent.Connected]. The server replays
     * nothing, so a collector treats every [ServerEvent.Connected] as a cue to fetch the messages and
     * the pending forms and permissions again.
     *
     * The flow fails with [ApiException] only when retrying cannot help, as for
     * [ApiError.Unauthorized]. An event this app does not know becomes [ServerEvent.Unknown], so it
     * never fails the flow. Heartbeats are not emitted.
     *
     * @param types The server event types to receive, for example `form.created`, to save data.
     *   Empty means all of them.
     */
    fun events(types: Set<String> = emptySet()): Flow<ServerEvent>
}
