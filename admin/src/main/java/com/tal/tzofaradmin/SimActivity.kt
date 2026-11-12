package com.tal.tzofaradmin

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * הדמיה של התרעה מההיסטוריה, כמו מסך ההתרעה המלא בצופר חירום: צבע לפי הסוג, אייקון, כותרת, היישובים,
 * ספירה לאחור (מתחילה עכשיו, כאילו ההתרעה הגיעה הרגע) וההקראה. בלי צליל ובלי מפה.
 */
class SimActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var queue: List<String> = emptyList()
    private var muted = false
    private val firedAt = System.currentTimeMillis()
    private var countdown: TextView? = null
    private var cdLabel: TextView? = null
    private var shelter = 0
    private var title = ""
    private val STAY_MS = 10 * 60 * 1000L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun card(color: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(r).toFloat() }

    private fun noShelter(t: String) = t.contains("רעידת אדמה") || t.contains("צונאמי")
    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

    private val tick = object : Runnable {
        override fun run() {
            val cd = countdown ?: return
            val now = System.currentTimeMillis()
            val enterEnd = firedAt + shelter * 1000L
            val stay = if (noShelter(title)) 0L else STAY_MS
            val (label, value) = when {
                now < enterEnd -> "זמן התגוננות" to mmss(((enterEnd - now + 999) / 1000).toInt())
                now < enterEnd + stay -> "נשארים במרחב המוגן" to mmss(((enterEnd + stay - now + 999) / 1000).toInt())
                else -> "" to "ממתינים להודעת סיום אירוע"
            }
            cdLabel?.text = label; cdLabel?.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
            cd.textSize = if (label.isEmpty()) 20f else 38f
            cd.text = value
            if (label.isNotEmpty()) ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val a = Data.simAlert ?: run { finish(); return }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val level = Data.level(a)
        title = a.title
        shelter = Data.shelterOf(a) ?: 90
        val bg = Color.parseColor(when (level) { 1 -> "#F08C00"; 2 -> "#2E7D32"; else -> "#D50000" })
        @Suppress("DEPRECATION") window.statusBarColor = bg
        @Suppress("DEPRECATION") window.navigationBarColor = bg
        val update = Data.isUpdate(a.raw)
        val (head, note) = when (level) {
            1 -> "מבזק פיקוד העורף" to (if (title.startsWith("התרעה מקדימה - ")) title else "התרעה מקדימה")
            2 -> "עדכון פיקוד העורף" to (if (update) a.raw else "האירוע הסתיים")
            else -> title to when { title.contains("רעידת אדמה") -> "צאו לשטח פתוח"; title.contains("צונאמי") -> "התרחקו מחוף הים"; else -> "היכנסו למרחב המוגן" }
        }
        val icon = when {
            level == 1 -> R.drawable.ic_list_pre; level == 2 -> R.drawable.ic_list_end
            title.contains("כלי טיס") -> R.drawable.ic_alert_aircraft; title.contains("מחבלים") -> R.drawable.ic_alert_infiltration
            title.contains("רעידת אדמה") -> R.drawable.ic_alert_quake; title.contains("צונאמי") -> R.drawable.ic_alert_tsunami
            title.contains("רדיולוגי") -> R.drawable.ic_alert_radio
            title.contains("חומרים מסוכנים") || title.contains("כימי") -> R.drawable.ic_alert_hazmat
            title.contains("רקטות") || title.contains("טילים") -> R.drawable.ic_alert_rocket
            else -> R.drawable.ic_list_siren
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(tv("הדמיה · " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("he")).format(java.util.Date(a.ts)), 13f).apply {
            setTextColor(Color.parseColor("#E6FFFFFF")); setPadding(0, dp(14), 0, 0)
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(22), dp(16), dp(14))
            addView(ImageView(this@SimActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(tv(head, 28f, true))
            addView(tv(note, 20f).apply { setPadding(0, dp(2), 0, 0) })
        })

        // היישובים כתגיות (נגלל כשיש הרבה)
        val chips = Flow(this, dp(8))
        a.areas.distinct().forEach { n ->
            chips.addView(tv(n, 22f, true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); background = card("#33FFFFFF", 18) })
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.34f).toInt()
        root.addView(object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(chips, ViewGroup.LayoutParams(-1, -2)) },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(10), dp(16), 0) })

        if (level == 2 && !update) root.addView(tv("ניתן לצאת מהמרחב המוגן", 19f).apply { setPadding(dp(16), dp(14), dp(16), 0) })
        if (level == 1) {
            if (title.isNotBlank() && title != "התרעה מקדימה") root.addView(tv(title, 19f).apply { setPadding(dp(16), dp(14), dp(16), 0) })
            root.addView(tv("במקרה של קבלת התרעה, יש להיכנס למרחב המוגן ולשהות בו עד לקבלת הנחיה מפורשת", 19f).apply { setPadding(dp(16), dp(6), dp(16), 0) })
        }
        if (level == 0) {
            cdLabel = tv("", 13f); countdown = tv("", 38f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); includeFontPadding = false }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(cdLabel); addView(countdown)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(12), dp(10), 0) })
            ui.post(tick)
        }

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { setPadding(dp(10), dp(10), dp(10), dp(16)) }
        fun btn(ic: Int, label: String, click: (TextView) -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
            background = card("#40000000", 24)
            val l = tv(label, 13f)
            addView(ImageView(this@SimActivity).apply { setImageResource(ic) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(l, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            setOnClickListener { click(l) }
        }
        buttons.addView(btn(R.drawable.ic_volume_off, "השתק") { l -> muted = true; stopSound(); l.text = "הושתק"; l.alpha = 0.6f },
            LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(btn(R.drawable.ic_close, "סגור") { finish() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(buttons)
        setContentView(root)

        // רק ההקראה, כמו באפליקציה (בלי צליל)
        queue = Data.clips(a) ?: emptyList()
        next()
    }

    private fun next() {
        if (muted || queue.isEmpty()) { stopSound(); return }
        val path = queue[0]; queue = queue.drop(1)
        player?.release()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        try {
            val u = Data.url(path)
            if (u != null) mp.setDataSource(u) else assets.openFd(path).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { if (player === it) next() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) next(); true }
            player = mp; mp.prepareAsync()
        } catch (_: Exception) { mp.release(); player = null; next() }
    }
    private fun stopSound() { player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }; player = null; queue = emptyList() }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); stopSound(); super.onDestroy() }
}

/** שורות של תגיות שנשברות לשורה הבאה כשאין מקום (מימין לשמאל), ממורכזות */
class Flow(c: android.content.Context, private val gap: Int) : ViewGroup(c) {
    override fun onMeasure(w: Int, h: Int) {
        val max = MeasureSpec.getSize(w)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); measureChild(v, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), h)
            if (x > 0 && x + v.measuredWidth > max) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(max, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val max = r - l
        val rows = mutableListOf<MutableList<View>>(mutableListOf())
        var x = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (x > 0 && x + v.measuredWidth > max) { rows += mutableListOf<View>(); x = 0 }
            rows.last() += v; x += v.measuredWidth + gap
        }
        var y = 0
        for (row in rows) {
            val w = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            var cx = max - (max - w) / 2   // מימין
            val rh = row.maxOfOrNull { it.measuredHeight } ?: 0
            for (v in row) { cx -= v.measuredWidth; v.layout(cx, y, cx + v.measuredWidth, y + v.measuredHeight); cx -= gap }
            y += rh + gap
        }
    }
}
