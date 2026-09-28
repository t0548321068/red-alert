package com.tal.redalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "prefs"
    private const val MAX_HISTORY = 20

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** ערים לסינון. ריק = כל הארץ */
    fun cities(c: Context): List<String> =
        (sp(c).getString("cities", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setCities(c: Context, list: List<String>) =
        sp(c).edit().putString("cities", list.joinToString(",")).apply()

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    data class Entry(val time: String, val title: String, val body: String, val level: Int)

    /** היסטוריית התראות, החדשה ראשונה */
    fun history(c: Context): List<Entry> {
        val arr = try { JSONArray(sp(c).getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("time"), o.optString("title"), o.optString("body"), o.optInt("level"))
        }
    }

    @Synchronized
    fun addHistory(c: Context, e: Entry) {
        val list = listOf(e) + history(c).take(MAX_HISTORY - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("time", it.time).put("title", it.title)
                .put("body", it.body).put("level", it.level))
        }
        sp(c).edit().putString("history", arr.toString()).apply()
    }
}
