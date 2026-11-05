package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.ui.R

/** The texts of the transfer notification. [text] is null when there is nothing to add to [title]. */
internal data class NotificationText(val title: String, val text: String?, val cancel: String)

/** Words [summary] for the notification: file names only, never a folder or a path. */
internal fun notificationText(context: Context, summary: TransferSummary): NotificationText {
    val title = when (summary.phase) {
        TransferPhase.AwaitingAnswer -> context.getString(R.string.files_transfer_notification_awaiting_title, summary.name)
        TransferPhase.Waiting -> context.getString(R.string.files_transfer_notification_waiting, summary.name)
        TransferPhase.Running -> when (summary.direction) {
            TransferDirection.Upload -> context.getString(R.string.files_transfer_notification_uploading, summary.name)
            TransferDirection.Download -> context.getString(R.string.files_transfer_notification_downloading, summary.name)
        }
    }
    val text = when {
        summary.phase == TransferPhase.AwaitingAnswer -> context.getString(R.string.files_transfer_notification_awaiting_text)
        summary.others > 0 ->
            context.resources.getQuantityString(R.plurals.files_transfer_notification_more, summary.others, summary.others)
        else -> null
    }
    return NotificationText(title, text, context.getString(R.string.files_transfer_notification_cancel))
}

/** Creates the quiet channel the notification posts to; creating it again only renames it. */
internal fun ensureTransferChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(TransferService.CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(context.getString(R.string.files_transfer_notification_channel))
        .setShowBadge(false)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

/**
 * The ongoing notification of the service: [summary]'s job with determinate progress when its size
 * is known and no bar otherwise, never an endless one, and an action that cancels every unfinished
 * job. Null [summary] is the moment between the start and the first look at the queue.
 *
 * On a locked screen only the channel's name shows, as file names are the user's business.
 * Tapping it opens the app.
 */
internal fun transferNotification(context: Context, summary: TransferSummary?): Notification {
    val builder = NotificationCompat.Builder(context, TransferService.CHANNEL_ID)
        .setSmallIcon(
            if (summary?.direction == TransferDirection.Download) android.R.drawable.stat_sys_download
            else android.R.drawable.stat_sys_upload,
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(context))
    openApp(context)?.let(builder::setContentIntent)
    if (summary == null) {
        return builder.setContentTitle(context.getString(R.string.files_transfer_notification_preparing)).build()
    }
    val text = notificationText(context, summary)
    builder.setContentTitle(text.title)
    text.text?.let(builder::setContentText)
    summary.percent?.let { builder.setProgress(100, it, false) }
    builder.addAction(
        NotificationCompat.Action.Builder(0, text.cancel, cancelAll(context)).build(),
    )
    return builder.build()
}

private fun publicVersion(context: Context): Notification =
    NotificationCompat.Builder(context, TransferService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(context.getString(R.string.files_transfer_notification_channel))
        .setShowWhen(false)
        .build()

private fun cancelAll(context: Context): PendingIntent = PendingIntent.getService(
    context,
    0,
    TransferService.cancelIntent(context),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private fun openApp(context: Context): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
