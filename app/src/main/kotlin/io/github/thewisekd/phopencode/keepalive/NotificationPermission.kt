package io.github.thewisekd.phopencode.keepalive

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Whether to ask now for the permission to post the keep-alive notification: only on API 33 and
 * later, where the notification is hidden without it, while it is not [granted], the app has not
 * asked before ([askedBefore]), and a connection is active in [state] (see [keepsAlive]).
 */
internal fun asksForNotifications(sdkInt: Int, granted: Boolean, askedBefore: Boolean, state: ConnectionState): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore && keepsAlive(state)

/** Whether the app asked for the notification permission once already. */
interface NotificationAsks {
    fun askedBefore(): Boolean

    fun recordAsked()
}

/**
 * The real [NotificationAsks]: an empty file in the app's private no-backup directory, so a new
 * install, even one restored from a backup, starts without it. It holds nothing at all, so nothing
 * about a server and nothing secret. A failed write is dropped: at worst the app asks once more on
 * a later start, and never more than once while it runs.
 */
class FileNotificationAsks(context: Context) : NotificationAsks {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    @Volatile
    private var asked: Boolean? = null

    override fun askedBefore(): Boolean = asked ?: file.exists().also { asked = it }

    override fun recordAsked() {
        asked = true
        try {
            file.createNewFile()
        } catch (failed: IOException) {
            // Remembered in memory for this run; see the class.
        } catch (failed: SecurityException) {
            // The same.
        }
    }

    internal companion object {
        const val FILE_NAME = "keepalive_notifications_asked"
    }
}

/**
 * On API 33 and later, asks once per install for the permission to post the keep-alive
 * notification: the first time a connection is active while the activity is at least started,
 * the permission is missing and [asks] has no record of asking (see [asksForNotifications]). The
 * record is written before the system dialog is launched, so whatever the answer, and after a
 * rotation or a recreation while the dialog is up, the app never asks again by itself.
 *
 * It only waits for the state; it never delays the connection. A refusal changes nothing: the
 * service runs and the connection stays up, only the notification stays hidden. A grant while a
 * connection is active has the service post its current notification again, so it shows without
 * a reconnect.
 */
@Composable
internal fun AskForKeepAliveNotifications(connection: KeepAliveConnection, asks: NotificationAsks) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The answer may reach an activity recreated while the dialog was up; the launcher hands it on.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && keepsAlive(connection.state.value)) repostKeepAlive(context.applicationContext)
    }
    LaunchedEffect(connection, asks, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (asks.askedBefore()) return@repeatOnLifecycle
            connection.state.first { state ->
                asksForNotifications(Build.VERSION.SDK_INT, canNotify(context), asks.askedBefore(), state)
            }
            asks.recordAsked()
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (missing: ActivityNotFoundException) {
                // No one to ask: the service runs without its notification.
            }
        }
    }
}

private fun canNotify(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Has [KeepAliveService] post its notification again. Sent from the visible app, so the start is
 * allowed; one that is refused all the same is dropped, as the next state change posts it anyway.
 */
private fun repostKeepAlive(context: Context) {
    try {
        context.startService(KeepAliveService.repostIntent(context))
    } catch (refused: IllegalStateException) {
        // Not allowed now; the next state change posts the notification.
    } catch (refused: SecurityException) {
        // The same.
    }
}
