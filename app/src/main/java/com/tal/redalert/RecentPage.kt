package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row
import com.tal.redalert.MainActivity.Perm
import com.tal.redalert.MainActivity.SameHeight

// עמוד "התרעות אחרונות": הכל / שלי, מחולק לימים, נבנה בחלקים לפי הגלילה
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

/** "שלי": רק ההתרעות שהאפליקציה באמת הקפיצה לי (ההיסטוריה שלי) - לא מהארכיון */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.mineOf(all: List<Prefs.Entry>): List<Prefs.Entry> = Prefs.history(this)

/** בורר הכל / האזורים שלי (בכותרת, ליד כפתור המפה), עם מספר ההתרעות בכל אחד */
internal fun MainActivity.buildRecentSeg(seg: LinearLayout) {
    seg.removeAllViews()
    val data = archiveCache
    listOf(false to "הכל", true to "שלי").forEach { (mine, name) ->
        val sel = recentMine == mine
        val n = when { data == null -> null; mine -> mineOf(data).size; else -> data.size }
        val label = if (n == null) name else "$name ($n)"
        // רוחב קבוע לכל כפתור (לפי הנוסח המודגש) - בלי קפיצה כשמחליפים בחירה
        val w = android.graphics.Paint().apply { textSize = 14f * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.DEFAULT_BOLD }.measureText(label).toInt() + dp(28)
        seg.addView(text(label, 14f, if (sel) Color.WHITE else C.MUTED, bold = sel).apply {
            gravity = Gravity.CENTER
            minWidth = w
            setPadding(0, dp(7), 0, dp(7))
            if (sel) background = GradientDrawable().apply { setColor(Color.parseColor("#4C7DFF")); cornerRadius = dp(16).toFloat() }
            setOnClickListener {
                if (sel) return@setOnClickListener
                recentMine = mine; buildRecentSeg(seg)
                val c = recentList; val data = archiveCache
                if (c != null && data != null) fillRecent(c, data)
            }
        }, LinearLayout.LayoutParams(-2, -2))
    }
}

internal fun MainActivity.showRecent() = onePage("התרעות אחרונות",
    action = R.drawable.ic_map to { startActivity(Intent(this, MapActivity::class.java)) },
    headerExtra = { h ->
        val seg = LinearLayout(this).apply { background = graphite(22); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        buildRecentSeg(seg); recentSegView = seg
        h.addView(seg, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
    }) { c ->
    recentList = c
    val cached = archiveCache
    if (cached != null) { fillRecent(c, cached); return@onePage }
    val loading = text("טוען את כל ההתראות…", 14f, C.MUTED2).apply { setPadding(dp(28), dp(12), dp(28), dp(12)) }
    c.addView(loading)
    Thread {
        // הארכיון + מה שנשמר בטלפון מפעמים קודמות (נשמר עד שנה אחורה)
        val list = try { OrefArchive.fetchAndKeep(this) } catch (_: Exception) { emptyList() }
            .ifEmpty { Prefs.feed(this) }   // אין כלום - מה שהאפליקציה קלטה בעצמה
        runOnUiThread {
            archiveCache = list
            fillRecent(c, list)
        }
        // פעם אחת: השלמה מהארכיון שנה אחורה, ואז מרעננים את הרשימה
        if (try { OrefArchive.backfill(this) } catch (_: Exception) { false }) {
            val full = try { OrefArchive.fetchAndKeep(this) } catch (_: Exception) { emptyList() }
            if (full.isNotEmpty()) runOnUiThread { archiveCache = full; recentList?.let { l ->
                // רענון ברקע - נשארים באותו מקום ברשימה
                val sv = generateSequence(l.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
                val y = sv?.scrollY ?: 0
                fillRecent(l, full, recentShown); sv?.post { sv.scrollTo(0, y) } } }
        }
    }.start()
}

/** minShown: רענון ברקע - בונים לפחות כמה שכבר הוצג, כדי שהמקום ברשימה יישמר */
internal fun MainActivity.fillRecent(c: LinearLayout, all: List<Prefs.Entry>, minShown: Int = 0) {
    c.removeAllViews()
    recentSegView?.let { buildRecentSeg(it) }   // מספרים מעודכנים
    val items = if (!recentMine) all else mineOf(all)
    val muted = C.MUTED2
    recentItems = items; recentShown = 0; recentDay = null; recentCard = null
    if (items.isEmpty()) {
        c.addView(text(if (recentMine) "אין התראות באזורים שלך" else "אין התראות עדיין", 14f, muted).apply { setPadding(dp(28), dp(12), dp(28), dp(12)) })
        return
    }
    appendRecent(c, maxOf(RECENT_CHUNK, minShown))
    // גלילה קרוב לסוף - מוסיפים עוד
    generateSequence(c.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()?.let { sv ->
        sv.setOnScrollChangeListener { v, _, y, _, _ ->
            val content = (v as ScrollView).getChildAt(0) ?: return@setOnScrollChangeListener
            if (recentShown < recentItems.size && y + v.height >= content.height - dp(1500)) appendRecent(c, RECENT_CHUNK)
        }
    }
}

internal fun MainActivity.appendRecent(c: LinearLayout, count: Int) {
    val muted = C.MUTED2
    val end = minOf(recentItems.size, recentShown + count)
    for (idx in recentShown until end) {
        val e = recentItems[idx]
        val day = if (e.ts > 0) fullDayLabel(e.ts) else ""
        var card = recentCard
        if (card == null || day != recentDay) {
            recentDay = day
            if (day.isNotEmpty()) c.addView(text(day, 13f, muted).apply { setPadding(dp(28), dp(14), dp(28), dp(6)) })
            card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = graphite(28)
                clipToOutline = true
            }
            recentCard = card
            c.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), 0) })
        } else card.addView(View(this).apply { setBackgroundColor(C.DIV) }, LinearLayout.LayoutParams(-1, dp(1)))
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // לחיצה: המפה על האזורים של ההתראה
            setOnClickListener {
                // חוזרים מהמפה לאותו מקום ברשימה
                val sv = generateSequence(c.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
                val y = sv?.scrollY ?: 0
                restoreScroll = { sv?.scrollTo(0, y) }
                startActivity(Intent(this@appendRecent, MapActivity::class.java)
                    .putExtra("focus", e.body).putExtra("focusTs", e.ts).putExtra("focusAll", true)
                    .putExtra("focusLevel", e.level).putExtra("focusTitle", e.title))   // ההתרעה שנלחצה - בצבע שלה
            }
        }
        val col = when (e.level) {
            AlertService.LEVEL_PRE -> "#F08C00"
            AlertService.LEVEL_END -> "#2E7D32"
            else -> "#E53935"
        }
        val icon = AlertUi.icon(e.title, e.level)
        row.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(col)) }
            addView(android.widget.ImageView(this@appendRecent).apply { AlertUi.setIcon(this, icon) },
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title, 15f, C.TEXT))
        texts.addView(text(e.body.replace("ברחבי הארץ", AreaData.ALL_COUNTRY), 12f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(if (e.ts > 0) recentTimeFmt.format(java.util.Date(e.ts)) else e.time.take(5), 12f, muted),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(row)
    }
    recentShown = end
}

/** "היום" / "אתמול" / תאריך */
internal fun MainActivity.dayLabel(ts: Long): String {
    val k = java.util.Calendar.getInstance()
    fun key(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val today = key(k)
    val e = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    k.add(java.util.Calendar.DAY_OF_YEAR, -1)
    return when (key(e)) {
        today -> "היום"
        key(k) -> "אתמול"
        else -> "%02d/%02d".format(e.get(java.util.Calendar.DAY_OF_MONTH), e.get(java.util.Calendar.MONTH) + 1)
    }
}

/** כותרת יום ב"התרעות אחרונות": כינוי (היום / אתמול / שלשום / לפני שבוע...) + תאריך מלא */
internal fun MainActivity.fullDayLabel(ts: Long): String {
    val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date(ts))
    fun midnight(ms: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    // הפרש בימים קלנדריים (עיגול - בגלל מעבר שעון קיץ/חורף)
    val days = Math.round((midnight(System.currentTimeMillis()) - midnight(ts)) / 86_400_000.0).toInt()
    val nick = when {
        days <= 0 -> "היום"
        days == 1 -> "אתמול"
        days == 2 -> "שלשום"
        days < 7 -> ""
        days < 14 -> "לפני שבוע"
        days < 21 -> "לפני שבועיים"
        days < 30 -> "לפני 3 שבועות"
        days < 60 -> "לפני חודש"
        days < 90 -> "לפני חודשיים"
        days < 120 -> "לפני 3 חודשים"
        else -> ""
    }
    return if (nick.isEmpty()) date else "$nick · $date"
}
