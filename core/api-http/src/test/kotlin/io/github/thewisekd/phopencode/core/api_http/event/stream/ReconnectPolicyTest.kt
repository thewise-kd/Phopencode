package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {
    @Test
    fun `the delay doubles from the first one up to the cap`() {
        val policy = ReconnectPolicy(jitter = 0.0)
        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds },
            (1..7).map(policy::delayFor),
        )
    }

    @Test
    fun `a huge attempt number stays at the cap`() {
        val policy = ReconnectPolicy(jitter = 0.0)
        assertEquals(30.seconds, policy.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun `a small multiplier keeps growing until it reaches the cap`() {
        val policy = ReconnectPolicy(multiplier = 1.01, jitter = 0.0)
        val delays = (1..400).map(policy::delayFor)
        val capAt = delays.indexOf(30.seconds)
        assertTrue(capAt in 300..399, "reached the cap at attempt ${capAt + 1}")
        assertTrue(delays.take(capAt + 1).zipWithNext().all { (a, b) -> b >= a }, "never shrinks")
        assertTrue(delays[capAt - 1] > 29.seconds, "grows right up to the cap: ${delays[capAt - 1]}")
        assertTrue(delays.drop(capAt).all { it == 30.seconds })
        assertEquals(30.seconds, policy.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun `a huge multiplier does not overflow`() {
        val policy = ReconnectPolicy(multiplier = 1e300, jitter = 0.0)
        assertEquals(listOf(1.seconds, 30.seconds, 30.seconds, 30.seconds), listOf(1, 2, 3, Int.MAX_VALUE).map(policy::delayFor))
    }

    @Test
    fun `a multiplier of 1 keeps the delay constant`() {
        val policy = ReconnectPolicy(multiplier = 1.0, jitter = 0.0)
        assertEquals(listOf(1.seconds, 1.seconds, 1.seconds), listOf(1, 2, Int.MAX_VALUE).map(policy::delayFor))
    }

    @Test
    fun `jitter only shortens the delay, by at most its share`() {
        val policy = ReconnectPolicy(jitter = 0.2, random = Random(42))
        val delays = (1..2_000).map { policy.delayFor(4) }
        assertTrue(delays.all { it in 6_400.milliseconds..8.seconds }, delays.toString())
        assertTrue(delays.toSet().size > 100, "jitter must spread the delays")
    }

    @Test
    fun `failures retrying can fix are retried, the others are not`() {
        val policy = ReconnectPolicy()
        val retried = listOf(
            ApiError.Network,
            ApiError.Timeout,
            ApiError.MalformedResponse,
            ApiError.Server(500),
            ApiError.Server(503),
            ApiError.Unexpected(),
            ApiError.Unexpected(408),
            ApiError.Unexpected(429),
        )
        val final = listOf(
            ApiError.Unauthorized,
            ApiError.Forbidden,
            ApiError.NotFound,
            ApiError.BadRequest(),
            ApiError.Conflict(),
            ApiError.Closed,
            ApiError.Unexpected(302),
            ApiError.VersionTooOld("1.0.0"),
        )
        retried.forEach { assertTrue(policy.isRetryable(it), "$it") }
        final.forEach { assertFalse(policy.isRetryable(it), "$it") }
    }

    @Test
    fun `nonsense settings are refused`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(initialDelay = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(initialDelay = 2.seconds, maxDelay = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(jitter = 1.0) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(silenceTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(closedCheckInterval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy().delayFor(0) }
    }

    @Test
    fun `the default silence limit outlasts several heartbeats`() {
        // The server sends a heartbeat every 15 s.
        assertTrue(ReconnectPolicy().silenceTimeout >= 45.seconds)
    }
}
