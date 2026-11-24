package com.ezpnix.writeon.presentation.screens.edit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    /**
     * UX-13: extra that tells [MainActivity] which note the tapped notification belongs to.
     *
     * The value stays `noteId` because that is the contract the home-screen widgets already use
     * (`widget/ui/Note.kt`, `AddNoteWidget`); MainActivity reads one key for both entry points.
     */
    const val EXTRA_NOTE_ID = "noteId"

    /**
     * SEC-05: whether the notification currently posted shows a Vault note.
     *
     * In-process only. That is enough for the eviction path (the session it could decrypt with
     * lives in the same process); a notification left over from a previous process is already
     * redacted, see [showPinnedNotification].
     */
    @Volatile
    private var lastShownIsVault = false

    @Volatile
    private var channelReady = false

    private fun ensureChannel(context: Context) {
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

    /**
     * Posts (or replaces) the pinned-note notification.
     *
     * @param noteId the note a tap should open; -1 keeps the plain launch behaviour.
     * @param isVaultNote a Vault note is redacted unconditionally: its body is plaintext in the
     *   editor only because the passphrase is loaded, and neither the shade nor the lock screen is
     *   behind that passphrase. This used to depend on [LockEntryPoint] alone, so an install with a
     *   Vault passphrase but no app lock posted the full text.
     */
    fun showPinnedNotification(
        context: Context,
        title: String,
        content: String,
        noteId: Int = -1,
        isVaultNote: Boolean = false,
    ) {
        if (
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            // A distinct data URI and request code keep two notes' PendingIntents apart even when
            // the extra alone would be identical.
            .setData(Uri.parse("writeon://pinned-note/$noteId"))
            .putExtra(EXTRA_NOTE_ID, noteId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            noteId.coerceAtLeast(0),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // When an app lock is configured the notification shade itself must not carry the note: it
        // sits outside the app's lock gate, visible in the status bar, in expanded previews and in
        // screenshots, to anyone holding the phone. This mirrors the widget, which renders a
        // placeholder for the same reason. The redaction uses the app's own strings so it reads as
        // "a pinned note exists", not as note content. SEC-05 adds the Vault: its passphrase is a
        // credential even when no app lock is configured.
        val lockManager = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, LockEntryPoint::class.java)
                .lockManager()
        }.getOrNull()
        val redacted = isVaultNote || lockManager?.anyLockEnabled() == true

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
            // VISIBILITY_SECRET keeps even the redacted title off the lock screen; PRIVATE would
            // still show it there for a redacted app-lock note.
            .setVisibility(
                if (redacted) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PRIVATE
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        builder.setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_no_content))
                .build()
        )

        lastShownIsVault = isVaultNote
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelPinnedNotification(context: Context) {
        lastShownIsVault = false
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * SEC-05: drops the notification only when it showed a Vault note.
     *
     * Called when the Vault session is evicted. A regular pinned note on an install without an app
     * lock keeps working; its text is not tied to the passphrase that just went away.
     */
    fun cancelVaultPinnedNotification(context: Context) {
        if (lastShownIsVault) cancelPinnedNotification(context)
    }
}