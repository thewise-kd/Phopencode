package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When [EventStream] gives up on a connection and how long it waits before the next one.
 *
 * @param initialDelay The wait after the first failure in a row.
 * @param maxDelay The longest wait; each failure in a row multiplies the wait by [multiplier] until
 *   it reaches this.
 * @param jitter Share of a wait taken off at random, from 0 up to but not including 1, so that many
 *   phones do not reconnect in step after a server restart. 0.2 turns a wait of 10 s into 8 to 10 s.
 * @param silenceTimeout A connection that sends nothing at all, not a byte, for this long is
 *   counted as dropped. The server sends `: heartbeat` every 15 s, so the default allows three to go
 *   missing. A dead network often shows only this way: no error comes, the bytes just stop.
 * @param healthyAfter A connection that stayed up this long was healthy: the wait after it drops
 *   starts again from [initialDelay]. A server that accepts and then drops at once keeps backing off.
 * @param closedCheckInterval How often a wait checks whether the client was closed, so that a
 *   closed client ends the flow at once and not after a long wait.
 * @param random The source of [jitter].
 */
internal class ReconnectPolicy(
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0,
    val jitter: Double = 0.2,
    val silenceTimeout: Duration = 45.seconds,
    val healthyAfter: Duration = 10.seconds,
    val closedCheckInterval: Duration = 500.milliseconds,
    private val random: Random = Random.Default,
) {
    init {
        require(initialDelay.isPositive()) { "initialDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must not be below initialDelay" }
        require(multiplier >= 1.0) { "multiplier must be at least 1" }
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in [0, 1)" }
        require(silenceTimeout.isPositive()) { "silenceTimeout must be positive" }
        require(!healthyAfter.isNegative()) { "healthyAfter must not be negative" }
        require(closedCheckInterval.isPositive()) { "closedCheckInterval must be positive" }
    }

    /** The wait after the [attempt]th failure in a row, counting from 1. */
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt counts from 1" }
        val maxMs = maxDelay.inWholeMilliseconds.toDouble()
        val initialMs = initialDelay.inWholeMilliseconds.toDouble()
        val growths = attempt - 1
        // From this many growths on, the delay is at the cap. Comparing with it first means pow()
        // only ever runs below the cap, so it cannot overflow, whatever the multiplier.
        val growthsToCap = if (multiplier > 1.0) ln(maxMs / initialMs) / ln(multiplier) else Double.POSITIVE_INFINITY
        val capped = if (growths >= growthsToCap) maxMs else minOf(initialMs * multiplier.pow(growths), maxMs)
        val jittered = capped * (1.0 - jitter * random.nextDouble())
        return jittered.toLong().coerceAtLeast(1).milliseconds
    }

    /**
     * Whether another connection may get past [error]. A refused password, a missing route or a
     * closed client stay as they are, so retrying them would only loop: the flow fails instead.
     */
    fun isRetryable(error: ApiError): Boolean = when (error) {
        ApiError.Network, ApiError.Timeout, ApiError.MalformedResponse, is ApiError.Server -> true
        is ApiError.Unexpected -> error.status == null || error.status in TRANSIENT_STATUSES
        ApiError.Unauthorized, ApiError.Forbidden, ApiError.NotFound, ApiError.Closed,
        is ApiError.BadRequest, is ApiError.Conflict, is ApiError.VersionTooOld,
        -> false
    }

    private companion object {
        /** Request Timeout and Too Many Requests: a later try may pass. */
        val TRANSIENT_STATUSES = setOf(408, 429)
    }
}
