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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/** ניהול צופר: מסך ראשי עם מצב כללי, ורשימות של יישובים, משפטי הקראה, צלילים והקלטות בלי יישוב */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val GREEN = Color.parseColor("#30D158"); private val ORANGE = Color.parseColor("#FF9F0A")
    private val RED = Color.parseColor("#FF453A"); private val MUTED = Color.parseColor("#8E8E93")
    private val CARD = Color.parseColor("#1C1C1E"); private val LINE = Color.parseColor("#2C2C2E")
    private val BLUE = Color.parseColor("#0A84FF")

    private enum class Page { MAIN, AREAS, PHRASES, SOUNDS, ORPHANS }
    private var page = Page.MAIN
    private var filter = 0          // 0 הכל · 1 חסרים · 2 סומנו לתיקון
    private var query = ""
    private var loading = false
    private var error = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun icon(res: Int, color: Int = Color.WHITE, size: Int = 22) = ImageView(this).apply {
        setImageResource(res); setColorFilter(color); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 26) }
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(10)) }

    // ---- דגלים: יישוב / הקלטה שמוקראים לא נכון ----
    private fun flags() = getSharedPreferences("admin", MODE_PRIVATE).getStringSet("flags", emptySet())!!
    private fun toggleFlag(key: String) {
        val s = flags().toMutableSet(); if (!s.remove(key)) s.add(key)
        getSharedPreferences("admin", MODE_PRIVATE).edit().putStringSet("flags", s).apply()
    }

    // ---- השמעה ----
    private var player: MediaPlayer? = null
    private var playing: String? = null
    private fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null; playing = null; refreshList()
    }
    private fun play(path: String) {
        if (playing == path) { stop(); return }
        stop()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        try {
            val u = Data.url(path)
            if (u != null) mp.setDataSource(u)
            else assets.openFd(path).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) { stop(); toast("הקובץ לא נפתח") }; true }
            player = mp; playing = path
            mp.prepareAsync()
        } catch (_: Exception) { mp.release(); toast("הקובץ לא נמצא") }
        refreshList()
    }
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        @Suppress("DEPRECATION") window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION") window.navigationBarColor = Color.BLACK
        load()
    }
    override fun onPause() { stop(); super.onPause() }

    private fun load() {
        if (loading) return
        loading = true; error = ""; show()
        Thread {
            try { Data.load(applicationContext) } catch (e: Exception) { error = e.message ?: "שגיאה" }
            ui.post { loading = false; show() }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.MAIN) { page = Page.MAIN; query = ""; stop(); show() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun show() = when (page) { Page.MAIN -> main(); else -> list() }

    // ---- כותרת ----
    private fun header(root: LinearLayout, title: String, back: Boolean) {
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(14), dp(14))
            if (back) addView(icon(R.drawable.ic_back, size = 24).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
                setOnClickListener { onBackPressed() }
            })
            addView(text(title, if (back) 21f else 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (!back) addView(icon(R.drawable.ic_refresh, if (loading) MUTED else Color.WHITE).apply { setOnClickListener { load() } })
        })
    }

    // ---- מסך ראשי ----
    private fun main() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
        header(root, "ניהול צופר", false)

        val a = Data.areas
        val noVoice = a.count { !it.voiceOk }; val noShelter = a.count { it.shelter == null }
        val noDistrict = a.count { it.district == null }; val orphans = Data.orphans.size
        val issues = noVoice + noShelter + noDistrict + orphans
        val ready = Data.loadedAt > 0

        // מצב כללי
        val (color, title, sub) = when {
            loading && !ready -> Triple(MUTED, "טוען…", "מוריד את הנתונים מהשרת")
            error.isNotEmpty() -> Triple(RED, "הטעינה נכשלה", error)
            issues == 0 && Data.serverOk -> Triple(GREEN, "הכל תקין", "נבדק מול השרת · ${ago(Data.loadedAt)}")
            !Data.serverOk -> Triple(ORANGE, "השרת לא זמין", "נבדק רק מול האפליקציה · הקלטות היישובים לא נבדקו")
            else -> Triple(ORANGE, "נמצאו $issues דברים לבדוק", "נבדק מול השרת · ${ago(Data.loadedAt)}")
        }
        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(icon(if (color == GREEN) R.drawable.ic_ok else R.drawable.ic_warn, color, 30).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 17f, bold = true)); addView(text(sub, 12f, MUTED))
                })
            })
        }, lp())
        if (!ready) return

        val n = java.text.NumberFormat.getIntegerInstance()
        root.addView(card().apply {
            nav(this, R.drawable.ic_pin, "אזורים ויישובים", n.format(a.size), GREEN) { open(Page.AREAS, 0) }
            nav(this, R.drawable.ic_mic, "הקראת שמות", "${n.format(a.size - noVoice)}/${n.format(a.size)}",
                if (noVoice == 0) GREEN else RED) { open(Page.AREAS, if (noVoice > 0) 1 else 0) }
            nav(this, R.drawable.ic_msg, "משפטי הקראה", Data.phrases.size.toString(), GREEN) { open(Page.PHRASES, 0) }
            nav(this, R.drawable.ic_vol, "צלילי התרעה", Data.sounds.size.toString(), GREEN) { open(Page.SOUNDS, 0) }
        }, lp())

        root.addView(card().apply {
            nav(this, 0, "חסרה הקראה", noVoice.toString(), if (noVoice == 0) GREEN else RED, noVoice > 0) { open(Page.AREAS, 1) }
            nav(this, 0, "חסר זמן מיגון", noShelter.toString(), if (noShelter == 0) GREEN else ORANGE, noShelter > 0) { open(Page.AREAS, 1) }
            nav(this, 0, "בלי מחוז", noDistrict.toString(), if (noDistrict == 0) GREEN else ORANGE, noDistrict > 0) { open(Page.AREAS, 1) }
            nav(this, 0, "הקלטה בלי יישוב", orphans.toString(), if (orphans == 0) GREEN else ORANGE, orphans > 0) { open(Page.ORPHANS, 0) }
            val fl = flags().count { !it.startsWith("voice/") }
            nav(this, R.drawable.ic_flag, "סומנו לתיקון", fl.toString(), if (fl == 0) MUTED else RED, fl > 0) { open(Page.AREAS, 2) }
        }, lp())
    }

    private fun nav(c: LinearLayout, ic: Int, title: String, end: String, endColor: Int, clickable: Boolean = true, go: () -> Unit) {
        if (c.childCount > 0) c.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(14), dp(14))
            if (ic != 0) addView(icon(ic, size = 20).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
            addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(end, 14f, endColor))
            if (clickable) { addView(icon(R.drawable.ic_chev, MUTED, 18)); setOnClickListener { go() } }
        })
    }

    private fun open(p: Page, f: Int) { page = p; filter = f; query = ""; show() }

    private fun ago(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when { s < 60 -> "עכשיו"; s < 3600 -> "לפני ${s / 60} דק׳"; else -> "לפני ${s / 3600} שע׳" }
    }

    // ---- רשימות ----
    private class Item(val title: String, val sub: String, val path: String?, val flagKey: String?, val bad: Boolean)
    private var items: List<Item> = emptyList()
    private var adapter: BaseAdapter? = null

    private fun list() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        header(root, when (page) {
            Page.AREAS -> "אזורים ויישובים"; Page.PHRASES -> "משפטי הקראה"
            Page.SOUNDS -> "צלילי התרעה"; else -> "הקלטה בלי יישוב" }, true)

        if (page == Page.AREAS) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = round(CARD, 16); setPadding(dp(14), 0, dp(14), 0)
                addView(icon(R.drawable.ic_search, MUTED, 18))
                addView(EditText(this@MainActivity).apply {
                    hint = "חיפוש יישוב"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 15f
                    background = null; setSingleLine(); setText(query)
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refreshList() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
            }, lp())
            val chips = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(10)) }
            listOf("הכל", "חסרים", "סומנו לתיקון").forEachIndexed { i, t ->
                chips.addView(text(t, 13f).apply {
                    background = round(if (i == filter) BLUE else LINE, 16); setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { filter = i; list() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            root.addView(chips)
        }

        val count = text("", 12f, MUTED).apply { setPadding(dp(22), 0, dp(22), dp(6)) }
        root.addView(count)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup) = row(items[p], p == 0, p == items.size - 1)
        }
        root.addView(ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, dp(24)); clipToPadding = false
            adapter = this@MainActivity.adapter
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        countView = count
        refreshList()
    }
    private var countView: TextView? = null

    private fun shelter(s: Int?) = when (s) { null -> "אין זמן מיגון"; 0 -> "מיידי"; 90 -> "דקה וחצי"; 60 -> "דקה"; else -> "$s שנ׳" }

    private fun refreshList() {
        if (page == Page.MAIN) return
        val fl = flags()
        items = when (page) {
            Page.AREAS -> Data.areas.filter {
                (query.isEmpty() || it.name.contains(query)) &&
                    when (filter) { 1 -> it.missing; 2 -> it.name in fl; else -> true }
            }.map { a ->
                val sub = listOf(a.district ?: "אין מחוז", shelter(a.shelter)).joinToString(" · ") +
                    (if (!a.voiceOk) " · אין הקראה" else "") + (a.voice?.let { "\n" + it.substringAfterLast('/') } ?: "")
                Item(a.name, sub, a.voice?.takeIf { a.voiceOk }, a.name, a.missing)
            }
            Page.PHRASES -> Data.phrases.map { Item(it.title, it.path.removePrefix("voice/"), it.path, it.path, false) }
            Page.SOUNDS -> Data.sounds.map { Item(it.title, it.path.substringAfterLast('/'), it.path, null, false) }
            else -> Data.orphans.map { Item(it.title, it.path.substringAfterLast('/'), it.path, null, true) }
        }
        countView?.text = "${items.size} פריטים"
        adapter?.notifyDataSetChanged()
    }

    private fun row(it: Item, first: Boolean, last: Boolean): View {
        val r = dp(26).toFloat()
        val bg = GradientDrawable().apply {
            setColor(CARD)
            cornerRadii = floatArrayOf(if (first) r else 0f, if (first) r else 0f, if (first) r else 0f, if (first) r else 0f,
                if (last) r else 0f, if (last) r else 0f, if (last) r else 0f, if (last) r else 0f)
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = bg; setPadding(dp(18), dp(12), dp(14), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(it.title, 15f))
                addView(text(it.sub, 12f, if (it.bad) ORANGE else MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            it.flagKey?.let { k ->
                addView(icon(R.drawable.ic_flag, if (k in flags()) RED else MUTED, 20).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
                    setOnClickListener { toggleFlag(k); refreshList() }
                })
            }
            val path = it.path
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LINE) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(if (path != null && playing == path) R.drawable.ic_stop else R.drawable.ic_play)
                    setColorFilter(if (path == null) MUTED else Color.WHITE)
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                if (path != null) setOnClickListener { play(path) }
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
        }
        wrap.addView(row)
        if (!last) wrap.addView(View(this).apply { setBackgroundColor(LINE) },
            FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { setMargins(dp(18), 0, dp(18), 0) })
        return wrap
    }
}
