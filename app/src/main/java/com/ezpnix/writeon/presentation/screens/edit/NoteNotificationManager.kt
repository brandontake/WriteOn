package com.ezpnix.writeon.presentation.screens.edit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.MainActivity
import com.ezpnix.writeon.presentation.lock.LockEntryPoint
import dagger.hilt.android.EntryPointAccessors

object NoteNotificationManager {
    private const val CHANNEL_ID = "note_channel"
    private const val NOTIFICATION_ID = 1

    @Volatile
    private var channelReady = false

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Creating a channel is idempotent but not free, and this used to run on every pin/unpin.
        if (channelReady) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_pinned_notes),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_pinned_notes_description)
            setShowBadge(false)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    fun showPinnedNotification(context: Context, title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // When an app lock is configured the notification shade itself must not carry the note: it
        // sits outside the app's lock gate, visible in the status bar, in expanded previews and in
        // screenshots, to anyone holding the phone. This mirrors the widget, which renders a
        // placeholder for the same reason. The redaction uses the app's own strings so it reads as
        // "a pinned note exists", not as note content.
        val lockManager = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, LockEntryPoint::class.java)
                .lockManager()
        }.getOrNull()
        val redacted = lockManager?.anyLockEnabled() == true

        val shownTitle = if (redacted) context.getString(R.string.app_name) else title
        val shownContent = if (redacted) context.getString(R.string.notif_no_content) else content

        // The note body sits in the notification, and a lock screen is exactly where somebody else
        // may be holding the phone. The real content stays private; anyone unlocking the device on
        // purpose can still read it, and anyone not looking at it does not get the text.
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(shownContent)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        builder.setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_no_content))
                .build()
        )

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelPinnedNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}