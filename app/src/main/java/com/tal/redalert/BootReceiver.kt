package com.tal.redalert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // אחרי הדלקת הטלפון, ואחרי כל עדכון של האפליקציה (אנדרואיד עוצר אותה בזמן העדכון)
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || replaced
        if (restart && Prefs.enabled(context)) {
            AlertService.start(context)
        } else if (restart) AlertService.showOff(context)   // כבוי - תזכורת שלא מוגן
        if (replaced) reopenAfterUpdate(context)
    }

    /**
     * עדכון שהתחיל מתוך האפליקציה (ב-10 הדקות האחרונות): פותחים אותה מחדש, כמו באנדרואיד החדשים.
     * אם אנדרואיד לא מרשה לפתוח מסך מהרקע (בלי הרשאת "הצגה מעל אפליקציות") - התראה שלחיצה עליה פותחת
     */
    private fun reopenAfterUpdate(c: Context) {
        val sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val at = sp.getLong("reopenAt", 0L)
        sp.edit().remove("reopenAt").apply()
        if (System.currentTimeMillis() - at > 10 * 60_000L) return
        val open = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (android.provider.Settings.canDrawOverlays(c)) {
            try { c.startActivity(open); return } catch (_: Exception) { }
        }
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("updated") == null) nm.createNotificationChannel(
            android.app.NotificationChannel("updated", "עדכון הותקן", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(4242, Notification.Builder(c, "updated")
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.notif_large))
            .setContentTitle("צופר חירום עודכן – " + Updater.versionLabel(c))
            .setContentText("לחץ לפתיחה")
            .setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }
}
