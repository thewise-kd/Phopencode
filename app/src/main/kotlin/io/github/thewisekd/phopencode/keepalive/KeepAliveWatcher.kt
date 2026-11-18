package io.github.thewisekd.phopencode.keepalive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Starts and stops [KeepAliveService]. An interface so the watcher's tests need no Android. */
interface KeepAliveStarter {
    fun start()

    fun stop()
}

/**
 * The real [KeepAliveStarter]. The stop is an intent too, not `stopService`: the system delivers
 * the two in the order they were sent, so the service has gone to the foreground before the stop
 * reaches it. Stopping a service that was asked for in the foreground before it called
 * `startForeground` would crash the app on API 28 and later.
 */
class ServiceKeepAliveStarter(private val context: Context) : KeepAliveStarter {
    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
    }

    override fun stop() {
        context.startService(KeepAliveService.stopIntent(context))
    }
}

/**
 * Keeps the process alive while a connection is active: it follows the connection's state on the
 * app [scope] and starts [KeepAliveService] when a connection becomes active, then stops it when
 * the connection ends (see [keepsAlive]). The steps of one connect, a drop and its reconnects are
 * one active stretch, so they start the service once. Before the first connect nothing is stopped.
 *
 * It is the only owner of the service's life: the service never stops because it saw the end, so a
 * quick end and reconnect that this watcher sees as one stretch keeps the service running.
 *
 * A start the system refuses is dropped: the connection goes on without the service, and the next
 * active stretch tries again, so nothing is retried in a loop. A refused stop, as when the service
 * is not running and the app is in the background, is dropped too: there is nothing to stop.
 *
 * The application calls [start] once; a second call does nothing.
 */
class KeepAliveWatcher(
    private val connection: KeepAliveConnection,
    private val scope: CoroutineScope,
    private val starter: KeepAliveStarter,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            connection.state.map(::keepsAlive).distinctUntilChanged().dropWhile { !it }.collect { active ->
                refusable { if (active) starter.start() else starter.stop() }
            }
        }
    }

    private fun refusable(call: () -> Unit) {
        try {
            call()
        } catch (refused: IllegalStateException) {
            // Not allowed now, as from the background (ForegroundServiceStartNotAllowedException is one).
        } catch (refused: SecurityException) {
            // Not allowed at all; the connection goes on as it is.
        }
    }
}
