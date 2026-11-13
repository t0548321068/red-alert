package com.tal.redalert.editor

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tal.redalert.AlertActivity
import com.tal.redalert.AlertPopupActivity
import com.tal.redalert.AlertService
import com.tal.redalert.AreaData
import com.tal.redalert.MainActivity
import com.tal.redalert.Prefs
import com.tal.redalert.Shabbat
import org.json.JSONObject

/**
 * "בדיקת התרעה" בעריכת צופר: אין כאן שירות התרעות, אז השורות מפעילות את ההתרעה מכאן -
 * בדיוק כמו testFire בשירות (אותה כותרת, "התרעת בדיקה", זמן התגוננות לפי המיקום, הסגנון שנבחר), בלי צליל
 */
object TestFire {
    private val ui = Handler(Looper.getMainLooper())

    /** השם בשורה -> הכותרת שנשלחת (כמו TEST_PRE / TEST_ALERT / TEST_END בהגדרות) */
    val ROWS = mapOf(
        "מבזק כללי" to "בדקות הקרובות צפויות להתקבל התרעות באזורך",
        "מבזק – איום מלבנון" to "איום מלבנון",
        "ירי רקטות וטילים" to "ירי רקטות וטילים",
        "חדירת כלי טיס עוין" to "חדירת כלי טיס עוין",
        "חדירת מחבלים" to "חדירת מחבלים",
        "רעידת אדמה" to "רעידת אדמה",
        "צונאמי" to "צונאמי",
        "אירוע רדיולוגי" to "אירוע רדיולוגי",
        "אירוע חומרים מסוכנים" to "אירוע חומרים מסוכנים",
        "האירוע הסתיים" to "האירוע הסתיים",
        "עדכון – הנחיות חזרה למבנים" to "רעידת אדמה - הנחיות חזרה למבנים")
    const val FULL = "הפעלת האירוע המלא"

    /** לחיצה על שורה בעמוד בדיקת התרעה. מחזיר true אם זו שורת בדיקה */
    fun tap(c: Context, label: String): Boolean {
        if (label == FULL) {
            ui.removeCallbacksAndMessages(null)
            val pre = Prefs.testSeq(c, "pre", ROWS.getValue("מבזק כללי"))
            val alert = Prefs.testSeq(c, "alert", "ירי רקטות וטילים")
            val end = Prefs.testSeq(c, "end", "האירוע הסתיים")
            fire(c, pre)
            ui.postDelayed({ val s = fire(c, alert); ui.postDelayed({ fire(c, end) }, s * 1000L + 20_000) }, 15_000)
            return true
        }
        val raw = ROWS[label] ?: return false
        ui.removeCallbacksAndMessages(null)
        fire(c, raw)
        return true
    }

    /** מחזיר את זמן ההתגוננות בשניות */
    fun fire(c: Context, raw: String): Int {
        val app = c.applicationContext
        val title = AlertService.displayTitle(raw)
        val level = AlertService.levelOf(title)
        val mine = Prefs.cities(app) + Prefs.nearbyAreas(app)
        val shelter = (AreaData.shelterSeconds(app, Prefs.nearbyAreas(app)) ?: AreaData.shelterSeconds(app, mine) ?: 15).coerceAtLeast(15)
        val body = "התרעת בדיקה"; val src = "בדיקה"; val now = System.currentTimeMillis()
        val areas = mine.joinToString(",")
        Prefs.setAlertDismissed(app, 0L)
        Prefs.setActiveAlert(app, JSONObject().put("title", title).put("body", body).put("level", level).put("shelter", shelter)
            .put("firedAt", now).put("source", src).put("mapAreas", areas))
        val st = if (Shabbat.active(app)) 0 else Prefs.alertStyleFor(app, level)
        if (st == 0 || st == 1)
            app.startActivity(Intent(app, if (st == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title", title).putExtra("body", body).putExtra("level", level).putExtra("shelter", shelter)
                .putExtra("firedAt", now).putExtra("source", src).putExtra("mapAreas", areas))
        else app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        return shelter
    }
}
