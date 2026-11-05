package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Keeps the app in the foreground while the app's one [TransferQueue] has unfinished jobs, so the
 * system does not stop a long transfer once the user leaves the app, and shows their progress in a
 * notification with an action that cancels them. It stops itself when the queue is idle.
 *
 * The transfers themselves run in the queue, on the app's scope; this service only follows its
 * jobs. The transfer screen starts it whenever it sees an unfinished job ([start]). Its type is
 * `dataSync`: on API 34 and later that needs its own permission, which the module's manifest
 * declares. Without the notification permission of API 33 and later, the service and the
 * transfers still run; the notification just does not show.
 *
 * The queue comes from the Koin the app started, looked up when the service is created.
 *
 * Grants: when the queue goes idle with the service running, it gives back the persisted URI grants
 * of the picked documents ([releaseTransferGrants]). If the system refuses the foreground start, or
 * the `dataSync` time limit ends the service, while transfers still run, the service stops at once
 * and the grants stay held until the queue next goes idle with the service running; they are never
 * kept beyond that, and nothing else in the app persists grants.
 */
class TransferService : Service() {
    private lateinit var queue: TransferQueue
    private var scope: CoroutineScope? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        queue = KoinPlatform.getKoin().get()
        ensureTransferChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val summary = summarize(queue.jobs.value)
        if (intent?.action == ACTION_CANCEL) cancelAll()
        if (!goForeground(summary)) {
            // Refused: stop at once, since running on would leave the foreground-start deadline
            // pending. Grants go back now only if no job needs them.
            if (summary == null) releaseTransferGrants(contentResolver)
            stopSelf()
            return START_NOT_STICKY
        }
        follow()
        if (summary == null) stopIdle()
        return START_NOT_STICKY
    }

    /**
     * The time limit of `dataSync` on API 35 and later: the jobs go on, the service leaves, whatever
     * start came last. Grants still held go back at the next idle stop.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun follow() {
        if (scope != null) return
        val following = MainScope()
        scope = following
        following.launch {
            queue.jobs.map(::summarize).distinctUntilChanged().collect { summary ->
                if (summary == null) {
                    stopIdle()
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, transferNotification(this@TransferService, summary))
                }
            }
        }
    }

    private fun cancelAll() {
        queue.jobs.value.filterNot { it.status.isFinished }.forEach { queue.cancel(it.id) }
    }

    /** False when the system refused, as when the app is not allowed to start one from the background. */
    private fun goForeground(summary: TransferSummary?): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            transferNotification(this, summary),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (refused: IllegalStateException) {
        false
    } catch (refused: SecurityException) {
        false
    }

    /** The queue is idle: no job needs a picked document any more, so their grants go back too. */
    private fun stopIdle() {
        releaseTransferGrants(contentResolver)
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    companion object {
        internal const val CHANNEL_ID = "files.transfers"
        internal const val NOTIFICATION_ID = 8_013
        private const val ACTION_CANCEL = "io.github.thewisekd.phopencode.feature.files.transfer.CANCEL_ALL"

        /**
         * Starts the service, which then follows the queue until it is idle. Called while the app is
         * in the foreground; should the system still refuse, the transfers run without it.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (refused: IllegalStateException) {
                // The transfers still run, without the notification.
            }
        }

        /** What the notification's cancel action sends this service. */
        internal fun cancelIntent(context: Context): Intent =
            Intent(context, TransferService::class.java).setAction(ACTION_CANCEL)
    }
}
