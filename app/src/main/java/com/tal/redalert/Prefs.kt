package com.tal.redalert

import android.content.Context

object Prefs {
    private const val FILE = "prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** ערים לסינון, מופרדות בפסיק. ריק = כל הארץ */
    fun cities(c: Context): List<String> =
        (sp(c).getString("cities", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setCities(c: Context, v: String) = sp(c).edit().putString("cities", v).apply()
    fun citiesRaw(c: Context): String = sp(c).getString("cities", "") ?: ""

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun lastAlert(c: Context) = sp(c).getString("last", "אין התראות עדיין") ?: ""
    fun setLastAlert(c: Context, v: String) = sp(c).edit().putString("last", v).apply()
}
