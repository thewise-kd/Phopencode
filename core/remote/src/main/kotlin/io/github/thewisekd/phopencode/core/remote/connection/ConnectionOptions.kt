package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before each reconnect attempt: attempt `n` (from 1) waits `delays[n - 1]`, and
 * attempts past the list wait its last entry. After [maxAttempts] failed attempts the manager stops
 * with the last error; null keeps trying.
 */
data class ReconnectPolicy(
    val delays: List<Duration> = listOf(1.seconds, 2.seconds, 5.seconds, 10.seconds, 30.seconds),
    val maxAttempts: Int? = 10,
) {
    init {
        require(delays.isNotEmpty()) { "Delays are empty" }
        require(delays.all { !it.isNegative() && it.isFinite() }) { "Delays must be finite and not negative" }
        require(maxAttempts == null || maxAttempts > 0) { "Attempt limit must be positive" }
    }

    fun delayBefore(attempt: Int): Duration = delays[(attempt - 1).coerceIn(0, delays.lastIndex)]
}

data class ConnectionOptions(
    val ssh: SshConnectOptions = SshConnectOptions(),
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
)
