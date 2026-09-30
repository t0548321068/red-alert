package com.tal.redalert

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/** זריחה ושקיעה לפי מיקום ותאריך (נוסחת NOAA), בלי אינטרנט */
object Sun {
    /** (זריחה, שקיעה) בדקות מתחילת היום, בשעון המקומי של הטלפון (כולל שעון קיץ/חורף) */
    fun times(lat: Double, lon: Double, ms: Long): Pair<Int, Int>? {
        val k = Calendar.getInstance().apply { timeInMillis = ms }
        val n = k.get(Calendar.DAY_OF_YEAR)
        val gamma = 2 * PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa !in -1.0..1.0) return null
        val ha = Math.toDegrees(acos(cosHa))
        val offsetMin = TimeZone.getDefault().getOffset(ms) / 60000.0
        val rise = 720 - 4 * (lon + ha) - eqTime + offsetMin
        val set = 720 - 4 * (lon - ha) - eqTime + offsetMin
        return rise.roundToInt() to set.roundToInt()
    }
}
