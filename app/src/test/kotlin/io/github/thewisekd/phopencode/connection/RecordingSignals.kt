package io.github.thewisekd.phopencode.connection

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionSignals

/** What a signal source told the connection, in order. */
internal class RecordingSignals : ConnectionSignals {
    val calls = mutableListOf<Pair<String, Any?>>()

    override fun networkAvailable(network: Any) {
        calls += "available" to network
    }

    override fun networkLost(network: Any) {
        calls += "lost" to network
    }

    override fun appForegrounded() {
        calls += "foreground" to null
    }
}
