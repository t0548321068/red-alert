package com.tal.tzofarinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** התראה על פנייה חדשה (נושא inbox - רק האפליקציה הזו רשומה אליו). לחיצה פותחת את הפנייה */
class InboxPush : FirebaseMessagingService() {
    companion object {
        fun subscribe() { try { FirebaseMessaging.getInstance().subscribeToTopic("inbox") } catch (_: Exception) { } }
        /** האפליקציה פתוחה - מרעננים את הרשימה במקום (נקבע ב-MainActivity) */
        @Volatile var onNew: (() -> Unit)? = null
    }

    override fun onNewToken(token: String) = subscribe()

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        if (d["type"] != "inbox") return
        val id = d["id"] ?: return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("inbox") == null)
            nm.createNotificationChannel(NotificationChannel("inbox", "פניות חדשות", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, id.hashCode(),
            Intent(this, MainActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id.hashCode(), Notification.Builder(this, "inbox")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("פנייה מ-" + (d["from"] ?: ""))
            .setContentText(d["text"] ?: "")
            .setStyle(Notification.BigTextStyle().bigText(d["text"] ?: ""))
            .setContentIntent(open).setAutoCancel(true).build())
        onNew?.invoke()
    }
}

internal fun Context.cancelNotif(id: String) =
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
