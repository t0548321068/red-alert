package com.tal.redalert

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * התראות מיידיות מהשרת שלנו (דרך Firebase): השרת בודק את פיקוד העורף וצופר ושולח לכל הטלפונים.
 * ההתרעה עוברת לשירות ההתרעות כמו מכל מקור אחר (אותו סינון אזורים ואותה מניעת כפילות)
 */
class PushService : FirebaseMessagingService() {

    companion object {
        const val TOPIC = "alerts"
        fun subscribe() {
            try { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) } catch (_: Exception) { }
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        if (d["type"] != "alert") return
        val title = d["title"] ?: return
        val areas = (d["areas"] ?: "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (areas.isEmpty()) return
        SourceHealth.ok("push")
        val svc = AlertService.instance
        if (svc != null) {
            Thread { try { svc.handle(title, areas, source = "push") } catch (_: Exception) { } }.start()
        } else {
            // השירות לא רץ - מעירים אותו (הודעה בעדיפות גבוהה מאפשרת להפעיל אותו מהרקע)
            if (!Prefs.enabled(this)) return
            val i = Intent(this, AlertService::class.java).setAction(AlertService.ACTION_PUSH_ALERT)
                .putExtra("title", title).putExtra("areas", areas.toTypedArray())
            try { startForegroundService(i) } catch (_: Exception) { }
        }
    }

    override fun onNewToken(token: String) { subscribe() }
}
