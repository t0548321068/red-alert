package com.tal.redalert.editor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** גיבוי לחיבור הקבוע (UpdateListener): כל 15 דקות מוודא שהוא רץ, ובודק גרסה חדשה גם אם החיבור נפל */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        UpdateListener.start(this)
        Update.check(this, background = true, done = { jobFinished(p, false) }) { }
        return true
    }
    override fun onStopJob(p: JobParameters) = false

    companion object {
        private const val ID = 7301
        private const val CHANNEL = "editor_update"

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if ((js.getPendingJob(ID)?.intervalMillis ?: 0L) == 15 * 60 * 1000L) return
            js.schedule(JobInfo.Builder(ID, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)   // ממשיך גם אחרי הפעלה מחדש של הטלפון
                .build())
        }

        /** כשהטלפון מבקש אישור להתקנה: התראה, ולחיצה עליה פותחת את האישור */
        fun notify(c: Context, confirm: Intent) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "עדכוני עריכת צופר", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(ID, android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(com.tal.redalert.R.mipmap.ic_editor)
                .setContentTitle("עריכת צופר – גרסה חדשה הורדה")
                .setContentText("לחץ כדי להתקין")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
