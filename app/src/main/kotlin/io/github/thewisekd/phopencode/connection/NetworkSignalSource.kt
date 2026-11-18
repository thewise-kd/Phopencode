package io.github.thewisekd.phopencode.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionSignals
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells [signals] when the device's default network comes and goes, through
 * `ConnectivityManager.registerDefaultNetworkCallback`. It passes the [Network] objects on as they
 * are: the manager only compares them, and decides what a change means. Nothing is logged.
 *
 * The application calls [start] once, for the life of the process; a second call does nothing. A
 * registration the system refuses leaves the manager as it was, with the network counted as
 * available.
 */
class NetworkSignalSource(private val context: Context, private val signals: ConnectionSignals) {
    private val started = AtomicBoolean(false)

    internal val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            signals.networkAvailable(network)
        }

        override fun onLost(network: Network) {
            signals.networkLost(network)
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (refused: SecurityException) {
            // No ACCESS_NETWORK_STATE: the connection reconnects on its timer alone.
        } catch (refused: RuntimeException) {
            // Too many callbacks registered in the process; the same as above.
        }
    }
}
