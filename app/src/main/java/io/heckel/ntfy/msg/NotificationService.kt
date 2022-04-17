package io.heckel.ntfy.msg

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.*

class NotificationService(val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")
        displayInternal(subscription, notification)
    }

    fun update(subscription: Subscription, notification: Notification) {
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.activeNotifications.find { it.id == notification.notificationId } != null
        } else {
            true
        }
        if (active) {
            Log.d(TAG, "Updating notification $notification")
            displayInternal(subscription, notification, update = true)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${decodeMessage(notification)}")
            notificationManager.cancel(notification.notificationId)
        }
    }

    fun createNotificationChannels() {
        (1..5).forEach { priority -> maybeCreateNotificationChannel(priority) }
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, update: Boolean = false) {
        val title = formatTitle(subscription, notification)
        val channelId = toChannelId(notification.priority)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, Colors.notificationIcon))
            .setContentTitle(title)
            .setOnlyAlertOnce(true) // Do not vibrate or play sound if already showing (updates!)
            .setAutoCancel(true) // Cancel when notification is clicked
        setStyleAndText(builder, notification) // Preview picture or big text style
        setClickAction(builder, subscription, notification)
        maybeSetSound(builder, update)
        maybeSetProgress(builder, notification)
        maybeAddOpenAction(builder, notification)
        maybeAddBrowseAction(builder, notification)
        maybeAddDownloadAction(builder, notification)
        maybeAddCancelAction(builder, notification)
        maybeAddCustomActions(builder, notification)

        maybeCreateNotificationChannel(notification.priority)
        notificationManager.notify(notification.notificationId, builder.build())
    }

    private fun maybeSetSound(builder: NotificationCompat.Builder, update: Boolean) {
        if (!update) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultSoundUri)
        } else {
            builder.setSound(null)
        }
    }

    private fun setStyleAndText(builder: NotificationCompat.Builder, notification: Notification) {
        val contentUri = notification.attachment?.contentUri
        val isSupportedImage = supportedImage(notification.attachment?.type)
        if (contentUri != null && isSupportedImage) {
            try {
                val resolver = context.applicationContext.contentResolver
                val bitmapStream = resolver.openInputStream(Uri.parse(contentUri))
                val bitmap = BitmapFactory.decodeStream(bitmapStream)
                builder
                    .setContentText(formatMessage(notification))
                    .setLargeIcon(bitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null))
            } catch (_: Exception) {
                val message = formatMessageMaybeWithAttachmentInfo(notification)
                builder
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            val message = formatMessageMaybeWithAttachmentInfo(notification)
            builder
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }
    }

    private fun formatMessageMaybeWithAttachmentInfo(notification: Notification): String {
        val message = formatMessage(notification)
        val attachment = notification.attachment ?: return message
        val infos = if (attachment.size != null) {
            "${attachment.name}, ${formatBytes(attachment.size)}"
        } else {
            attachment.name
        }
        if (attachment.progress in 0..99) {
            return context.getString(R.string.notification_popup_file_downloading, infos, attachment.progress, message)
        }
        if (attachment.progress == PROGRESS_DONE) {
            return context.getString(R.string.notification_popup_file_download_successful, message, infos)
        }
        if (attachment.progress == PROGRESS_FAILED) {
            return context.getString(R.string.notification_popup_file_download_failed, message, infos)
        }
        return context.getString(R.string.notification_popup_file, message, infos)
    }

    private fun setClickAction(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        if (notification.click == "") {
            builder.setContentIntent(detailActivityIntent(subscription))
        } else {
            try {
                val uri = Uri.parse(notification.click)
                val viewIntent = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, uri), PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(viewIntent)
            } catch (e: Exception) {
                builder.setContentIntent(detailActivityIntent(subscription))
            }
        }
    }

    private fun maybeSetProgress(builder: NotificationCompat.Builder, notification: Notification) {
        val progress = notification.attachment?.progress
        if (progress in 0..99) {
            builder.setProgress(100, progress!!, false)
        } else {
            builder.setProgress(0, 0, false) // Remove progress bar
        }
    }

    private fun maybeAddOpenAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val contentUri = Uri.parse(notification.attachment.contentUri)
            val intent = Intent(Intent.ACTION_VIEW, contentUri).apply {
                setDataAndType(contentUri, notification.attachment.type ?: "application/octet-stream") // Required for Android <= P
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_open), pendingIntent).build())
        }
    }

    private fun maybeAddBrowseAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_browse), pendingIntent).build())
        }
    }

    private fun maybeAddDownloadAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && listOf(PROGRESS_NONE, PROGRESS_FAILED).contains(notification.attachment?.progress)) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_download), pendingIntent).build())
        }
    }

    private fun maybeAddCancelAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && notification.attachment?.progress in 0..99) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_cancel), pendingIntent).build())
        }
    }

    private fun maybeAddCustomActions(builder: NotificationCompat.Builder, notification: Notification) {
        notification.actions?.forEach { action ->
            when (action.action) {
                "view" -> maybeAddViewUserAction(builder, action)
                "http-post" -> maybeAddHttpPostUserAction(builder, notification, action)
            }
        }
    }

    private fun maybeAddViewUserAction(builder: NotificationCompat.Builder, action: Action) {
        Log.d(TAG, "Adding user action $action")
        try {
            val url = action.url ?: return
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    private fun maybeAddHttpPostUserAction(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_HTTP)
            putExtra(BROADCAST_EXTRA_ACTION, action.action)
            putExtra(BROADCAST_EXTRA_URL, action.url)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
    }

    class UserActionBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received $intent")
            val type = intent.getStringExtra(BROADCAST_EXTRA_TYPE) ?: return
            val notificationId = intent.getStringExtra(BROADCAST_EXTRA_NOTIFICATION_ID) ?: return
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START, BROADCAST_TYPE_DOWNLOAD_CANCEL -> handleDownloadAction(context, type, notificationId)
                BROADCAST_TYPE_HTTP -> handleCustomUserAction(context, intent, type, notificationId)
            }
        }

        private fun handleDownloadAction(context: Context, type: String, notificationId: String) {
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START -> DownloadManager.enqueue(context, notificationId, userAction = true)
                BROADCAST_TYPE_DOWNLOAD_CANCEL -> DownloadManager.cancel(context, notificationId)
            }
        }

        private fun handleCustomUserAction(context: Context, intent: Intent, type: String, notificationId: String) {
            val action = intent.getStringExtra(BROADCAST_EXTRA_ACTION) ?: return
            val url = intent.getStringExtra(BROADCAST_EXTRA_URL) ?: return
            when (type) {
                BROADCAST_TYPE_HTTP -> UserActionManager.enqueue(context, notificationId, action, url)
            }
        }
    }

    private fun detailActivityIntent(subscription: Subscription): PendingIntent? {
        val intent = Intent(context, DetailActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) // Get the PendingIntent containing the entire back stack
        }
    }

    private fun maybeCreateNotificationChannel(priority: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Note: To change a notification channel, you must delete the old one and create a new one!

            val pause = 300L
            val channel = when (priority) {
                1 -> NotificationChannel(CHANNEL_ID_MIN, context.getString(R.string.channel_notifications_min_name), NotificationManager.IMPORTANCE_MIN)
                2 -> NotificationChannel(CHANNEL_ID_LOW, context.getString(R.string.channel_notifications_low_name), NotificationManager.IMPORTANCE_LOW)
                4 -> {
                    val channel = NotificationChannel(CHANNEL_ID_HIGH, context.getString(R.string.channel_notifications_high_name), NotificationManager.IMPORTANCE_HIGH)
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000
                    )
                    channel
                }
                5 -> {
                    val channel = NotificationChannel(CHANNEL_ID_MAX, context.getString(R.string.channel_notifications_max_name), NotificationManager.IMPORTANCE_HIGH) // IMPORTANCE_MAX does not exist
                    channel.enableLights(true)
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000,
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000,
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000
                    )
                    channel
                }
                else -> NotificationChannel(CHANNEL_ID_DEFAULT, context.getString(R.string.channel_notifications_default_name), NotificationManager.IMPORTANCE_DEFAULT)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun toChannelId(priority: Int): String {
        return when (priority) {
            1 -> CHANNEL_ID_MIN
            2 -> CHANNEL_ID_LOW
            4 -> CHANNEL_ID_HIGH
            5 -> CHANNEL_ID_MAX
            else -> CHANNEL_ID_DEFAULT
        }
    }

    companion object {
        private const val TAG = "NtfyNotifService"

        private const val BROADCAST_EXTRA_TYPE = "type"
        private const val BROADCAST_EXTRA_NOTIFICATION_ID = "notificationId"
        private const val BROADCAST_EXTRA_ACTION = "action"
        private const val BROADCAST_EXTRA_URL = "url"

        private const val BROADCAST_TYPE_DOWNLOAD_START = "io.heckel.ntfy.DOWNLOAD_ACTION_START"
        private const val BROADCAST_TYPE_DOWNLOAD_CANCEL = "io.heckel.ntfy.DOWNLOAD_ACTION_CANCEL"
        private const val BROADCAST_TYPE_HTTP = "io.heckel.ntfy.USER_ACTION_HTTP"

        private const val CHANNEL_ID_MIN = "ntfy-min"
        private const val CHANNEL_ID_LOW = "ntfy-low"
        private const val CHANNEL_ID_DEFAULT = "ntfy"
        private const val CHANNEL_ID_HIGH = "ntfy-high"
        private const val CHANNEL_ID_MAX = "ntfy-max"
    }
}
