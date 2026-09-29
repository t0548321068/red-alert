package com.tal.redalert

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * בחירת צליל לכל סוג התראה.
 * ערכים: "app:siren" / "app:beep" (צלילי האפליקציה), "device" (ברירת מחדל של הטלפון),
 * "silent", או כתובת של צליל מהטלפון. ריק = ברירת המחדל של אותו סוג.
 */
object Sounds {
    const val APP_SIREN = "app:siren"
    const val APP_BEEP = "app:beep"
    const val DEVICE = "device"
    const val SILENT = "silent"

    /** ברירת מחדל: ירי - הצופר של האפליקציה · מקדימה - צליל הטלפון (כמו עד עכשיו) · סיום - בלי צליל */
    private fun default(level: Int) = when (level) {
        AlertService.LEVEL_ALERT -> APP_SIREN
        AlertService.LEVEL_PRE -> DEVICE
        else -> SILENT
    }

    fun effective(c: Context, level: Int): String =
        Prefs.sound(c, level).ifEmpty { default(level) }

    fun uri(c: Context, value: String, level: Int): Uri? = when (value) {
        SILENT -> null
        APP_SIREN -> Uri.parse("android.resource://${c.packageName}/${R.raw.app_siren}")
        APP_BEEP -> Uri.parse("android.resource://${c.packageName}/${R.raw.app_beep}")
        DEVICE -> RingtoneManager.getDefaultUri(
            if (level == AlertService.LEVEL_ALERT) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        else -> Uri.parse(value)
    }

    fun label(c: Context, level: Int): String = when (val v = effective(c, level)) {
        APP_SIREN -> "🚨 צופר האפליקציה"
        APP_BEEP -> "🔔 צפצוף האפליקציה"
        DEVICE -> "📱 צליל הטלפון"
        SILENT -> "🔇 ללא צליל"
        else -> "🎵 " + (try { RingtoneManager.getRingtone(c, Uri.parse(v))?.getTitle(c) } catch (_: Exception) { null } ?: "מותאם")
    }
}
