package io.github.thewisekd.phopencode.keepalive

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.R

/** Creates the quiet channel the notification posts to; creating it again only renames it. */
internal fun ensureKeepAliveChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(KeepAliveService.CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(context.getString(R.string.keepalive_channel))
        .setSound(null, null)
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

/**
 * The ongoing, silent notification of [KeepAliveService]: one line for [status], tapping it opens
 * the app, and one action disconnects. It never shows the server's address, port, user or profile:
 * its only texts are the fixed strings of [status], the channel and the action.
 */
internal fun keepAliveNotification(context: Context, status: KeepAliveStatus): Notification =
    NotificationCompat.Builder(context, KeepAliveService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(context.getString(statusText(status)))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp(context))
        .addAction(
            NotificationCompat.Action.Builder(0, context.getString(R.string.keepalive_disconnect), disconnect(context)).build(),
        )
        .build()

private fun statusText(status: KeepAliveStatus): Int = when (status) {
    KeepAliveStatus.Connected -> R.string.keepalive_connected
    KeepAliveStatus.Reconnecting -> R.string.keepalive_reconnecting
    KeepAliveStatus.Connecting -> R.string.keepalive_connecting
}

private fun disconnect(context: Context): PendingIntent = PendingIntent.getService(
    context,
    0,
    KeepAliveService.disconnectIntent(context),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** The running [MainActivity] comes to the front as from the launcher; `singleTop` keeps it one. */
private fun openApp(context: Context): PendingIntent {
    val open = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
