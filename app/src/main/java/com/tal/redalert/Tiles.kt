package com.tal.redalert

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// כפתורים בלוח ההגדרות המהירות (גוללים מלמעלה): מצב ההגנה, השתקה זמנית ומצב שבת.
// מתעדכנים רק כשפותחים את הלוח - בלי שום עבודה ברקע

private fun Tile.set(label: String, sub: String, on: Boolean, icon: Int, c: Context) {
    this.label = label
    if (Build.VERSION.SDK_INT >= 29) subtitle = sub else this.label = "$label · $sub"
    state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    this.icon = Icon.createWithResource(c, icon)
    updateTile()
}

/** פתיחת האפליקציה וסגירת הלוח */
@Suppress("DEPRECATION")
private fun TileService.openApp() {
    val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (Build.VERSION.SDK_INT >= 34)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    else startActivityAndCollapse(i)
}

/** מוגן / לא מוגן / אין רשת - נגיעה פותחת את האפליקציה */
class StatusTile : TileService() {
    override fun onStartListening() = refresh()
    override fun onClick() = openApp()
    private fun refresh() {
        val t = qsTile ?: return
        val sched = Prefs.enabled(this) && Prefs.scheduleOffNow(this)
        val on = Prefs.enabled(this) && !sched
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true } catch (_: Exception) { true }
        val sub = when { !on && sched -> "כבוי לפי תזמון"; !on -> "לא מוגן"; !net -> "אין רשת"; else -> "מוגן" }
        t.set("צופר חירום", sub, on && net, if (on && net) R.drawable.ic_shield_ind else R.drawable.ic_shield_ind_off, this)
    }
}

/** השתקה זמנית: נגיעה משתיקה לשעה (כמו המתג בהגדרות), נגיעה נוספת מבטלת */
class MuteTile : TileService() {
    override fun onStartListening() = refresh()
    override fun onClick() {
        Prefs.setMuteUntil(this, if (Prefs.mutedNow(this)) 0L else System.currentTimeMillis() + 60 * 60_000)
        refresh()
    }
    private fun refresh() {
        val t = qsTile ?: return
        val u = Prefs.muteUntil(this)
        val muted = Prefs.mutedNow(this)
        val sub = when { !muted -> "כבוי"; u == Long.MAX_VALUE -> "עד שתבטל"; else -> "עד " + SimpleDateFormat("HH:mm", Locale.US).format(Date(u)) }
        t.set("השתקה", sub, muted, R.drawable.ic_volume_off, this)
    }
}

/** מצב שבת: נגיעה מחליפה כבוי → דלוק → אוטומטי → כבוי. דלוק בצבע כשהשבת פעילה עכשיו */
class ShabbatTile : TileService() {
    override fun onStartListening() = refresh()
    override fun onClick() {
        Prefs.setShabbatMode(this, when (Prefs.shabbatMode(this)) { Shabbat.OFF -> Shabbat.ON; Shabbat.ON -> Shabbat.AUTO; else -> Shabbat.OFF })
        refresh()
    }
    private fun refresh() {
        val t = qsTile ?: return
        val mode = Prefs.shabbatMode(this)
        val active = try { Shabbat.active(this) } catch (_: Exception) { mode == Shabbat.ON }
        val sub = Shabbat.label(mode) + if (mode == Shabbat.AUTO && active) " · פעיל" else ""
        t.set("מצב שבת", sub, active, R.drawable.ic_tile_shabbat, this)
    }
}
