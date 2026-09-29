package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** היסטוריה מלאה: ההתראות שלי / כל הארץ, עם חיפוש וסינון לפי זמן */
class HistoryActivity : Activity() {

    private var dark = true
    private var mine = true          // שלי / ארצי
    private var range = 1            // 0=היום 1=שבוע 2=הכל
    private var query = ""
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private val tabs = mutableListOf<TextView>()
    private val ranges = mutableListOf<TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(d: String, l: String) = Color.parseColor(if (dark) d else l)
    private val BG get() = c("#0E0E10", "#F2F2F7")
    private val CARD get() = c("#1C1C1E", "#FFFFFF")
    private val CHIP get() = c("#2C2C2E", "#E5E5EA")
    private val TEXT get() = c("#F2F2F2", "#1C1C1E")
    private val MUTED get() = c("#8E8E93", "#6E6E73")

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = Prefs.darkTheme(this)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar
                 else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.decorView.setBackgroundColor(BG)
        @Suppress("DEPRECATION")
        window.statusBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "היסטוריית התראות"
            textSize = 22f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
        })

        // שלי / ארצי
        val tabRow = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        listOf("ההתראות שלי", "כל הארץ").forEachIndexed { i, label ->
            val t = chip(label) { mine = i == 0; render() }
            tabs += t
            tabRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(tabRow)

        // חיפוש
        col.addView(EditText(this).apply {
            hint = "חיפוש לפי אזור או סוג…"
            setSingleLine()
            setTextColor(TEXT); setHintTextColor(MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(CARD) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { query = e.toString().trim(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // טווח זמן
        val rangeRow = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        listOf("היום", "שבוע", "הכל").forEachIndexed { i, label ->
            val t = chip(label) { range = i; render() }
            ranges += t
            rangeRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(rangeRow)

        count = TextView(this).apply { textSize = 12f; setTextColor(MUTED); setPadding(0, dp(12), 0, dp(4)) }
        col.addView(count)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        })
        render()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { onClick() }
    }

    private fun styleChip(t: TextView, on: Boolean) {
        t.setTextColor(if (on) Color.WHITE else TEXT)
        t.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (on) Color.parseColor("#FF3B30") else CHIP)
        }
    }

    private fun render() {
        tabs.forEachIndexed { i, t -> styleChip(t, (i == 0) == mine) }
        ranges.forEachIndexed { i, t -> styleChip(t, i == range) }

        val since = when (range) {
            0 -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            1 -> System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
            else -> 0L
        }
        val items = (if (mine) Prefs.history(this) else Prefs.feed(this))
            .filter { it.ts == 0L || it.ts >= since }
            .filter { query.isEmpty() || it.body.contains(query) || it.title.contains(query) }

        count.text = if (items.isEmpty()) "אין התראות" else "${items.size} התראות"
        list.removeAllViews()
        items.forEach { e -> list.addView(row(e)) }
    }

    private fun row(e: Prefs.Entry): View {
        val color = when (e.level) {
            AlertService.LEVEL_PRE -> "#FF9F0A"
            AlertService.LEVEL_END -> "#30D158"
            else -> "#FF3B30"
        }
        val card = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(CARD) }
        }
        card.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { topMargin = dp(6); marginEnd = dp(10) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(TEXT) })
        texts.addView(TextView(this).apply { text = e.body; textSize = 13f; setTextColor(MUTED) })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val stamp = if (e.ts > 0) {
            val k = Calendar.getInstance().apply { timeInMillis = e.ts }
            "%02d/%02d\n%s".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1,
                TimeFormat.time(this, e.ts))
        } else e.time
        card.addView(TextView(this).apply {
            text = stamp; textSize = 12f; setTextColor(MUTED); gravity = Gravity.END
        })
        return LinearLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
