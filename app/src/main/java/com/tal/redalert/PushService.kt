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
        if (d["type"] == "hb") {   // סימן חיים מהשרת + אילו מקורות תקינים אצלו
            AlertService.onServerHeartbeat(this, (d["up"] ?: "").split(',').filter { it.isNotEmpty() })
            return
        }
        if (d["type"] != "alert") return
        val title = d["title"] ?: return
        val src = d["src"] ?: ""
        var areas = (d["areas"] ?: "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        // טלגרם: רק שמות יישובים אמיתיים (כמו כשהטלפון קרא את הערוצים בעצמו)
        if (src.startsWith("tg:")) areas = areas.filter { AreaData.isKnown(this, it) }
        if (areas.isEmpty()) return
        val at = d["at"]?.toLongOrNull() ?: System.currentTimeMillis()
        val svc = AlertService.instance
        if (svc != null) {
            Thread { try { svc.handle(title, areas, at, "push:$src") } catch (_: Exception) { } }.start()
        } else {
            // השירות לא רץ - מעירים אותו (הודעה בעדיפות גבוהה מאפשרת להפעיל אותו מהרקע)
            if (!Prefs.enabled(this)) return
            val i = Intent(this, AlertService::class.java).setAction(AlertService.ACTION_PUSH_ALERT)
                .putExtra("title", title).putExtra("areas", areas.toTypedArray())
                .putExtra("at", at).putExtra("src", "push:$src")
            try { startForegroundService(i) } catch (_: Exception) { }
        }
    }

    override fun onNewToken(token: String) { subscribe() }
}
