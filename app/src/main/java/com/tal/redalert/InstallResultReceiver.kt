package com.tal.redalert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** תוצאת התקנת עדכון: אם אנדרואיד דורש אישור - פותחים את מסך האישור */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                // עדכון ברקע: אנדרואיד לא תמיד מרשה לפתוח מסך - גם התראה שלחיצה עליה פותחת את האישור
                if (!android.provider.Settings.canDrawOverlays(c)) askByNotification(c, confirm)
                try { c.startActivity(confirm) } catch (_: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // האפליקציה מתחלפת; BootReceiver פותח אותה מחדש
            else -> {
                c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().remove("reopenAt").apply()   // נכשל - לא פותחים מחדש
                Toast.makeText(c, "ההתקנה נכשלה", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askByNotification(c: Context, confirm: Intent) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("updated") == null) nm.createNotificationChannel(
            android.app.NotificationChannel("updated", "עדכון הותקן", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(4243, Notification.Builder(c, "updated")
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.notif_large))
            .setContentTitle("גרסה חדשה של צופר חירום מוכנה")
            .setContentText("לחץ כדי לאשר את ההתקנה")
            .setContentIntent(PendingIntent.getActivity(c, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build())
    }
}
