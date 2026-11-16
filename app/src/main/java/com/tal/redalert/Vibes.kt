package com.tal.redalert

import android.content.Context
import android.os.Build

/** עצירת רטט של התראה (כפתור "השתק") - הרטט עצמו לפי ערוץ ההתראה של הטלפון */
object Vibes {
    private fun vibrator(c: Context): android.os.Vibrator =
        if (Build.VERSION.SDK_INT >= 31)
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)

    fun stop(c: Context) = try { vibrator(c).cancel() } catch (_: Exception) { }
}
