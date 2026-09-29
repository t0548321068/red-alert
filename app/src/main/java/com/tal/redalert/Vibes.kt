package com.tal.redalert

import android.content.Context
import android.media.AudioAttributes
import android.os.Build

/** סוגי רטט לבחירה, לכל סוג התראה בנפרד */
object Vibes {
    val NAMES = arrayOf("ללא רטט", "קצר", "רגיל", "ארוך", "רציף (10 שניות)", "דופק", "SOS")

    const val NONE = 0; const val SHORT = 1; const val NORMAL = 2; const val LONG = 3
    const val CONTINUOUS = 4; const val HEARTBEAT = 5; const val SOS = 6

    private fun pattern(type: Int): LongArray? = when (type) {
        SHORT -> longArrayOf(0, 200)
        NORMAL -> longArrayOf(0, 800, 300, 800, 300, 800)                 // כמו בגרסאות הקודמות
        LONG -> longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500)
        CONTINUOUS -> longArrayOf(0, 10_000)
        HEARTBEAT -> longArrayOf(0, 150, 100, 150, 600, 150, 100, 150, 600, 150, 100, 150)
        SOS -> longArrayOf(0, 150, 150, 150, 150, 150, 400, 500, 150, 500, 150, 500, 400, 150, 150, 150, 150, 150)
        else -> null
    }

    /** ברירת מחדל: ירי - ארוך · מקדימה - רגיל (כמו עד עכשיו) · סיום - ללא (כמו עד עכשיו) */
    fun default(level: Int) = when (level) {
        AlertService.LEVEL_ALERT -> LONG
        AlertService.LEVEL_PRE -> NORMAL
        else -> NONE
    }

    fun play(c: Context, type: Int) {
        val p = pattern(type) ?: return
        try {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            v.vibrate(android.os.VibrationEffect.createWaveform(p, -1), attrs)   // נשמע גם במצב שקט
        } catch (_: Exception) { }
    }
}
