package io.github.thewisekd.phopencode.keepalive

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.thewisekd.phopencode.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Keeps the process in the foreground while a connection is active, so that Android neither
 * freezes it nor cuts its network once the user leaves the app, and the SSH keep-alives go on.
 * It holds no wake lock and has no time limit: its type is `specialUse`, which on API 34 and later
 * needs its own permission, declared in the manifest with the subtype that explains it.
 *
 * One owner: only [KeepAliveWatcher] starts and stops it, both through intents ([stopIntent] for
 * the stop), so they reach [onStartCommand] in the order they were sent and a stop never comes
 * before the start has gone to the foreground. The service only follows the state for its
 * notification and never stops because it saw the connection end: the watcher may see a quick end
 * and reconnect as one active stretch, and then nothing would start the service again.
 *
 * Its notification shows only whether the app is connected, connecting or reconnecting, never a
 * server detail. Its one action disconnects on the app scope; the watcher then stops the service.
 *
 * If the system refuses the foreground start, the service stops at once and does not try again;
 * the connection goes on without it. Without the notification permission of API 33 and later the
 * service still runs; the notification just does not show.
 *
 * The connection and the app scope come from the Koin the app started, looked up when the service
 * is created.
 */
class KeepAliveService : Service() {
    private lateinit var connection: KeepAliveConnection
    private lateinit var appScope: CoroutineScope
    private var scope: CoroutineScope? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val koin = KoinPlatform.getKoin()
        connection = koin.get()
        appScope = koin.get<AppScope>().scope
        ensureKeepAliveChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                appScope.launch { connection.disconnect() }
                // Created by the action alone, as after the service was gone: nothing will stop it.
                if (!inForeground) stopSelf(startId)
            }
            ACTION_STOP -> stop(startId)
            else -> {
                // Into the foreground first, whatever the state: startForegroundService owes it.
                val status = keepAliveStatus(connection.state.value) ?: KeepAliveStatus.Connecting
                if (goForeground(status)) follow() else stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /**
     * Updates the notification as the state changes. An ended connection leaves it as it was: the
     * watcher stops the service, or a reconnect follows that the watcher took for the same stretch.
     */
    private fun follow() {
        if (scope != null) return
        val following = MainScope()
        scope = following
        following.launch {
            connection.state.map(::keepAliveStatus).filterNotNull().distinctUntilChanged().collect { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, keepAliveNotification(this@KeepAliveService, status))
            }
        }
    }

    /** False when the system refused, as when the app is not allowed to start one from the background. */
    private fun goForeground(status: KeepAliveStatus): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, keepAliveNotification(this, status), foregroundType())
        inForeground = true
        true
    } catch (refused: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException of API 31 and later is one.
        false
    } catch (refused: SecurityException) {
        false
    }

    /** The watcher's stop. A start sent after it has a later id, so the service then stays. */
    private fun stop(startId: Int) {
        if (!stopSelfResult(startId)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
    }

    companion object {
        internal const val CHANNEL_ID = "connection.keepalive"
        internal const val NOTIFICATION_ID = 9_039
        private const val ACTION_DISCONNECT = "io.github.thewisekd.phopencode.keepalive.DISCONNECT"
        private const val ACTION_STOP = "io.github.thewisekd.phopencode.keepalive.STOP"

        /** `specialUse` exists from API 34; below it the type the manifest names is not passed. */
        private fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        /** What the watcher sends to stop the service, after the start it sent before. */
        internal fun stopIntent(context: Context): Intent =
            Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)

        /** What the notification's disconnect action sends this service. */
        internal fun disconnectIntent(context: Context): Intent =
            Intent(context, KeepAliveService::class.java).setAction(ACTION_DISCONNECT)
    }
}
