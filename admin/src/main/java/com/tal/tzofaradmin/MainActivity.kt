package com.tal.tzofaradmin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * ניהול צופר: מסך ראשי עם מצב כללי, יישובים, אזורים (מחוזות), משפטי הקראה, צלילים, היסטוריה,
 * סימון "נבדק ותקין" / "לתיקון", הערות, והצעות שינוי (ממתינות לאישור - לא מגיעות לטלפונים מכאן)
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val GREEN = Color.parseColor("#30D158"); private val ORANGE = Color.parseColor("#FF9F0A")
    private val RED = Color.parseColor("#FF453A"); private val MUTED = Color.parseColor("#8E8E93")
    private val CARD = Color.parseColor("#1C1C1E"); private val LINE = Color.parseColor("#2C2C2E")
    private val BLUE = Color.parseColor("#0A84FF")

    private enum class Page { MAIN, AREAS, DISTRICTS, PHRASES, SOUNDS, ORPHANS, HISTORY, ALERT, ITEM, EDITS }
    private var page = Page.MAIN
    private val stack = ArrayDeque<Page>()
    private var filter = 0          // יישובים: 0 הכל · 1 חסרים · 2 לא נבדקו · 3 תקינים · 4 לתיקון
    private var district: String? = null
    private var query = ""
    private var loading = false
    private var error = ""
    // היסטוריה
    private var history: List<Data.Alert>? = null
    private var histLoading = false
    private var histError = false
    private var histAt = 0L
    private var alert: Data.Alert? = null
    private var histQuery = ""
    // דף פריט (יישוב / הקלטה)
    private var itemKey = ""
    private var itemPath: String? = null
    private var itemArea: Data.Area? = null

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
    private fun divider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    // ---- סימונים: תקין / לתיקון (נשמר בשרת; עד שהשרת עונה - מוצג מיד) ----
    private val localStatus = HashMap<String, String>()
    private fun statusOf(key: String) = localStatus[key] ?: Review.status[key] ?: ""
    private fun setStatus(key: String, v: String) {
        if (!Review.loggedIn(this)) { toast("צריך להיכנס קודם (במסך הראשי)"); return }
        localStatus[key] = v; refresh()
        sendOp(Review.op("status", key).put("value", v)) { localStatus.remove(key) }
    }
    private fun cycle(key: String) = setStatus(key, when (statusOf(key)) { "" -> "ok"; "ok" -> "fix"; else -> "" })
    /** פעולה לשרת ברקע; done רץ בכל מקרה, ok = הודעה כשנשמר */
    private fun sendOp(op: JSONObject, ok: String? = null, done: () -> Unit = {}) {
        Thread {
            val r = Review.send(this, op)
            ui.post {
                done()
                if (!r) toast(if (Review.loggedIn(this)) "לא נשמר – אין חיבור לשרת" else "צריך להיכנס שוב") else ok?.let { toast(it) }
                refresh()
            }
        }.start()
    }
    private fun refresh() { if (page == Page.MAIN || page == Page.ITEM) show() else refreshList() }

    // ---- השמעה ----
    private var player: MediaPlayer? = null
    private var playing: String? = null
    private var queue: List<String> = emptyList()
    private fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        val was = playing != null
        player = null; playing = null; queue = emptyList()
        if (was) { if (page == Page.ITEM) playBtn() else refreshList() }
    }
    /** key = מה שמסומן כמושמע; paths = קובץ אחד או כמה ברצף (הקראת התרעה) */
    private fun play(key: String, paths: List<String> = listOf(key)) {
        if (playing == key) { stop(); return }
        stop()
        if (paths.isEmpty()) return
        playing = key; queue = paths.drop(1); start(paths[0])
    }
    private fun start(path: String) {
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        try {
            val u = Data.url(path)
            if (u != null) mp.setDataSource(u)
            else assets.openFd(path).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { p ->
                if (player !== p) return@setOnCompletionListener
                if (queue.isEmpty()) stop() else { p.release(); player = null; val n = queue[0]; queue = queue.drop(1); start(n) }
            }
            mp.setOnErrorListener { p, _, _ -> if (player === p) { stop(); toast("הקובץ לא נפתח") }; true }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) { mp.release(); stop(); toast("הקובץ לא נמצא") }
        if (page == Page.ITEM) playBtn() else refreshList()
    }
    private var playView: TextView? = null
    private fun playBtn() { playView?.text = if (playing != null && playing == itemPath) "עצירה" else "השמעה" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        @Suppress("DEPRECATION") window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION") window.navigationBarColor = Color.BLACK
        load()
        Update.check(this) { ui.post { if (page == Page.MAIN) show() } }
    }
    override fun onResume() {
        super.onResume()
        // חזרה מאישור "התקנת עדכונים" בהגדרות
        if (Update.needPermission && packageManager.canRequestPackageInstalls()) Update.check(this) { ui.post { if (page == Page.MAIN) show() } }
    }
    override fun onPause() { stop(); super.onPause() }

    private fun load() {
        if (loading) return
        loading = true; error = ""; show()
        Thread {
            try { Data.load(applicationContext) } catch (e: Exception) { error = e.message ?: "שגיאה" }
            if (Review.loggedIn(this)) Review.load(this)
            ui.post { loading = false; show() }
        }.start()
    }

    // ---- ניווט ----
    private fun go(p: Page) { stop(); stack.addLast(page); page = p; show() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.isEmpty()) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        stop()
        page = stack.removeLast()
        if (page == Page.MAIN) { query = ""; histQuery = ""; district = null }
        show()
    }
    private fun show() { when (page) { Page.MAIN -> main(); Page.ITEM -> item(); else -> list() } }

    // ---- כותרת ----
    private fun header(root: LinearLayout, title: String, back: Boolean) {
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(14), dp(14))
            if (back) addView(icon(R.drawable.ic_back, size = 24).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
                setOnClickListener { onBackPressed() }
            })
            addView(text(title, if (back) 21f else 26f, bold = true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
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

        if (Update.status.isNotEmpty()) root.addView(card().apply {
            nav(this, R.drawable.ic_refresh, Update.status, "", MUTED, Update.needPermission) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
        }, lp())
        if (!ready) return

        if (!Review.loggedIn(this)) loginCard(root)

        val n = java.text.NumberFormat.getIntegerInstance()
        root.addView(card().apply {
            nav(this, R.drawable.ic_pin, "יישובים", n.format(a.size), GREEN) { areasWith(0) }
            nav(this, R.drawable.ic_map, "אזורים", Data.districts.size.toString(), GREEN) { go(Page.DISTRICTS) }
            nav(this, R.drawable.ic_mic, "הקראת שמות", "${n.format(a.size - noVoice)}/${n.format(a.size)}",
                if (noVoice == 0) GREEN else RED) { areasWith(if (noVoice > 0) 1 else 0) }
            nav(this, R.drawable.ic_msg, "משפטי הקראה", Data.phrases.size.toString(), GREEN) { go(Page.PHRASES) }
            nav(this, R.drawable.ic_vol, "צלילי התרעה", Data.sounds.size.toString(), GREEN) { go(Page.SOUNDS) }
            nav(this, R.drawable.ic_clock, "היסטוריית התרעות", "30 יום", MUTED) { openHistory() }
        }, lp())

        if (Review.loggedIn(this)) {
            val ok = a.count { statusOf(it.name) == "ok" }; val fix = a.count { statusOf(it.name) == "fix" }
            root.addView(card().apply {
                nav(this, R.drawable.ic_ok, "נבדקו ותקינים", "${n.format(ok)}/${n.format(a.size)}", if (ok == a.size) GREEN else Color.WHITE) { areasWith(3) }
                nav(this, R.drawable.ic_flag, "לתיקון", fix.toString(), if (fix == 0) MUTED else RED, fix > 0) { areasWith(4) }
                nav(this, 0, "לא נבדקו", n.format(a.size - ok - fix), MUTED) { areasWith(2) }
                nav(this, R.drawable.ic_edit, "שינויים ממתינים לאישור", Review.edits.size.toString(),
                    if (Review.edits.isEmpty()) MUTED else ORANGE, Review.edits.isNotEmpty()) { go(Page.EDITS) }
                if (!Review.online) nav(this, R.drawable.ic_warn, "הסימונים לא נטענו מהשרת", "", ORANGE, false) { }
            }, lp())
        }

        root.addView(card().apply {
            nav(this, 0, "חסרה הקראה", noVoice.toString(), if (noVoice == 0) GREEN else RED, noVoice > 0) { areasWith(1) }
            nav(this, 0, "חסר זמן מיגון", noShelter.toString(), if (noShelter == 0) GREEN else ORANGE, noShelter > 0) { areasWith(1) }
            nav(this, 0, "בלי מחוז", noDistrict.toString(), if (noDistrict == 0) GREEN else ORANGE, noDistrict > 0) { areasWith(1) }
            nav(this, 0, "הקלטה בלי יישוב", orphans.toString(), if (orphans == 0) GREEN else ORANGE, orphans > 0) { go(Page.ORPHANS) }
        }, lp())
        root.addView(text("גרסה ${Update.current(this)}", 12f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
    }
    private fun areasWith(f: Int) { district = null; filter = f; query = ""; go(Page.AREAS) }

    private fun loginCard(root: LinearLayout) {
        root.addView(card().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(text("כניסה לסימונים ועריכה", 15f, bold = true))
            addView(text("אותו קוד כמו באתר. הסימונים וההערות נשמרים בשרת", 12f, MUTED))
            val field = EditText(this@MainActivity).apply {
                hint = "קוד כניסה"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 16f; setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val msg = text("", 12f, RED)
            addView(msg)
            addView(text("כניסה", 15f, BLUE, true).apply {
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    val code = field.text.toString().trim(); if (code.isEmpty()) { msg.text = "הקלד את הקוד"; return@setOnClickListener }
                    msg.setTextColor(MUTED); msg.text = "נכנס…"
                    Thread {
                        val err = Review.login(this@MainActivity, code)
                        if (err == null) Review.load(this@MainActivity)
                        ui.post { if (err == null) show() else { msg.setTextColor(RED); msg.text = err } }
                    }.start()
                }
            })
        }, lp())
    }

    private fun nav(c: LinearLayout, ic: Int, title: String, end: String, endColor: Int, clickable: Boolean = true, go: () -> Unit) {
        if (c.childCount > 0) divider(c)
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(14), dp(14))
            if (ic != 0) addView(icon(ic, size = 20).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
            addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(end, 14f, endColor).apply { maxWidth = dp(170); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (clickable) { addView(icon(R.drawable.ic_chev, MUTED, 18)); setOnClickListener { go() } }
        })
    }

    private fun ago(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when { s < 60 -> "עכשיו"; s < 3600 -> "לפני ${s / 60} דק׳"; else -> "לפני ${s / 3600} שע׳" }
    }

    private fun openHistory() {
        go(Page.HISTORY)
        if (histLoading || (history != null && System.currentTimeMillis() - histAt < 60_000)) return
        histLoading = true; histError = false; refreshList()
        Thread {
            val h = Data.history(30)
            ui.post {
                histLoading = false
                if (h != null) { history = h; histAt = System.currentTimeMillis() } else histError = history == null
                refreshList()
            }
        }.start()
    }

    private fun openItem(key: String, path: String?, area: Data.Area?) { itemKey = key; itemPath = path; itemArea = area; go(Page.ITEM) }

    // ---- רשימות ----
    private class Item(val title: String, val sub: String, val path: String?, val key: String?, val bad: Boolean,
        val seq: List<String>? = null, val tap: (() -> Unit)? = null, val chevron: Boolean = false)
    private var items: List<Item> = emptyList()
    private var adapter: BaseAdapter? = null
    private var countView: TextView? = null

    private fun list() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        header(root, when (page) {
            Page.AREAS -> district ?: "יישובים"; Page.DISTRICTS -> "אזורים"; Page.PHRASES -> "משפטי הקראה"
            Page.SOUNDS -> "צלילי התרעה"; Page.HISTORY -> "היסטוריית התרעות"; Page.EDITS -> "שינויים ממתינים"
            Page.ALERT -> alert?.title ?: ""; else -> "הקלטה בלי יישוב" }, true)

        if (page == Page.AREAS || page == Page.HISTORY) {
            val hist = page == Page.HISTORY
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = round(CARD, 16); setPadding(dp(14), 0, dp(14), 0)
                addView(icon(R.drawable.ic_search, MUTED, 18))
                addView(EditText(this@MainActivity).apply {
                    hint = if (hist) "חיפוש יישוב או סוג" else "חיפוש יישוב"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 15f
                    background = null; setSingleLine(); setText(if (hist) histQuery else query)
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            if (hist) histQuery = s.toString().trim() else query = s.toString().trim(); refreshList() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
            }, lp())
        }
        if (page == Page.AREAS) {
            val chips = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(10)) }
            listOf("הכל", "חסרים", "לא נבדקו", "תקינים", "לתיקון").forEachIndexed { i, t ->
                chips.addView(text(t, 13f).apply {
                    background = round(if (i == filter) BLUE else LINE, 16); setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { filter = i; list() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
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

    private fun shelter(s: Int?) = when (s) { null -> "אין זמן מיגון"; 0 -> "מיידי"; 90 -> "דקה וחצי"; 60 -> "דקה"; else -> "$s שנ׳" }
    private val FIELD_NAMES = mapOf("name" to "שם", "district" to "אזור", "shelter" to "זמן מיגון", "other" to "אחר")
    private fun note(k: String) = Review.notes[k]?.let { "\n✎ $it" } ?: ""

    private fun refreshList() {
        if (page == Page.MAIN || page == Page.ITEM) return
        items = when (page) {
            Page.AREAS -> Data.areas.filter {
                (district == null || it.district == district) &&
                (query.isEmpty() || it.name.contains(query)) &&
                    when (filter) { 1 -> it.missing; 2 -> statusOf(it.name) == ""; 3 -> statusOf(it.name) == "ok"; 4 -> statusOf(it.name) == "fix"; else -> true }
            }.map { a ->
                val sub = listOf(a.district ?: "אין מחוז", shelter(a.shelter)).joinToString(" · ") +
                    (if (!a.voiceOk) " · אין הקראה" else "") + (a.voice?.let { "\n" + it.substringAfterLast('/') } ?: "") + note(a.name)
                Item(a.name, sub, a.voice?.takeIf { a.voiceOk }, a.name, a.missing, tap = { openItem(a.name, a.voice?.takeIf { a.voiceOk }, a) })
            }
            Page.DISTRICTS -> Data.districts.map { (d, list) ->
                val ok = list.count { statusOf(it.name) == "ok" }; val fix = list.count { statusOf(it.name) == "fix" }
                val miss = list.count { it.missing }
                Item(d, "${list.size} יישובים" + (if (Review.loggedIn(this)) " · נבדקו $ok" else "") +
                    (if (fix > 0) " · לתיקון $fix" else "") + (if (miss > 0) " · חסרים $miss" else ""),
                    null, null, miss > 0, tap = { district = d; filter = 0; query = ""; go(Page.AREAS) }, chevron = true)
            }
            Page.PHRASES -> Data.phrases.map {
                Item(it.title, it.path.removePrefix("voice/") + note(it.path), it.path, it.path, false, tap = { openItem(it.path, it.path, null) })
            }
            Page.SOUNDS -> Data.sounds.map {
                Item(it.title, it.path.substringAfterLast('/') + note(it.path), it.path, it.path, false, tap = { openItem(it.path, it.path, null) })
            }
            Page.EDITS -> Review.edits.map { e ->
                Item(e.key, "${FIELD_NAMES[e.field] ?: e.field}: ${e.from.ifEmpty { "—" }} ← ${e.to}\n${when_(e.at)}", null, null, false, chevron = true,
                    tap = { val a = Data.areas.firstOrNull { it.name == e.key }; openItem(e.key, a?.voice?.takeIf { a.voiceOk }, a) })
            }
            Page.HISTORY -> (history ?: emptyList()).filter { a ->
                histQuery.isEmpty() || a.title.contains(histQuery) || a.areas.any { it.contains(histQuery) }
            }.map { a ->
                val names = a.areas.take(3).joinToString(", ") + if (a.areas.size > 3) " ועוד ${a.areas.size - 3}" else ""
                val missing = a.areas.any { r -> Data.match(r).none { it.voiceOk } }
                Item(a.title, when_(a.ts) + " · " + names, "alert:${a.ts}:${a.raw}", null, missing,
                    seq = Data.clips(a) ?: emptyList(), tap = { alert = a; go(Page.ALERT) })
            }
            Page.ALERT -> alert?.let { a ->
                val c = Data.clips(a)
                listOf(Item("הקראה מלאה", when_(a.ts) + " · " + a.areas.size + " יישובים" +
                    (if (c == null) "\nעדכון - באפליקציה מוקרא בקול של הטלפון" else ""),
                    if (c == null) null else "alert:${a.ts}:${a.raw}", null, false, seq = c ?: emptyList())) +
                a.areas.map { r ->
                    val m = Data.match(r); val v = m.firstOrNull { it.voiceOk }
                    val sub = if (m.isEmpty()) "לא נמצא ברשימת היישובים" else if (v == null) "אין הקראה"
                        else listOfNotNull(v.district, v.voice?.substringAfterLast('/')).joinToString(" · ")
                    val area = m.firstOrNull()
                    Item(r, sub, v?.voice, area?.name, v == null, tap = area?.let { ar -> { openItem(ar.name, v?.voice, ar) } })
                }
            } ?: emptyList()
            else -> Data.orphans.map { Item(it.title, it.path.substringAfterLast('/'), it.path, null, true) }
        }
        countView?.text = when {
            page == Page.HISTORY && histLoading && history == null -> "טוען את ההתרעות…"
            page == Page.HISTORY && histError -> "השרת לא ענה - נסה שוב"
            page == Page.HISTORY -> "${items.size} התרעות ב-30 הימים האחרונים"
            page == Page.EDITS -> if (items.isEmpty()) "אין שינויים ממתינים" else "לא נכנסים לטלפונים עד שמאשרים ומכניסים אותם"
            page == Page.ALERT -> ""
            page == Page.DISTRICTS -> "${items.size} אזורים"
            else -> "${items.size} פריטים"
        }
        adapter?.notifyDataSetChanged()
    }

    private val dayFmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("he"))
    private fun when_(ts: Long) = dayFmt.format(java.util.Date(ts))

    /** כפתור הסימון ברשימה: לא נבדק (אפור) → תקין (וי ירוק) → לתיקון (דגל אדום) → לא נבדק */
    private fun statusIcon(key: String) = when (statusOf(key)) {
        "ok" -> icon(R.drawable.ic_ok, GREEN, 22); "fix" -> icon(R.drawable.ic_flag, RED, 22); else -> icon(R.drawable.ic_ok, Color.parseColor("#48484A"), 22)
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
                addView(text(it.title, 15f, if (it.title.startsWith("התרעה מקדימה")) ORANGE
                    else if (page == Page.HISTORY && it.title == "האירוע הסתיים") GREEN else Color.WHITE))
                addView(text(it.sub, 12f, if (it.bad) ORANGE else MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            it.key?.let { k ->
                if (Review.loggedIn(this@MainActivity)) addView(statusIcon(k).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
                    setOnClickListener { cycle(k) }
                })
            }
            if (it.chevron) addView(icon(R.drawable.ic_chev, MUTED, 18))
            else {
                val path = it.path; val seq = it.seq
                addView(FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LINE) }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(if (path != null && playing == path) R.drawable.ic_stop else R.drawable.ic_play)
                        setColorFilter(if (path == null) MUTED else Color.WHITE)
                    }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    if (path != null) setOnClickListener { if (seq != null) play(path, seq) else play(path) }
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            }
        }
        it.tap?.let { t -> row.setOnClickListener { t() } }
        wrap.addView(row)
        if (!last) wrap.addView(View(this).apply { setBackgroundColor(LINE) },
            FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { setMargins(dp(18), 0, dp(18), 0) })
        return wrap
    }

    // ---- דף יישוב / הקלטה: השמעה, סימון, הערה, הצעת שינוי ----
    private fun item() {
        val a = itemArea
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
        header(root, a?.name ?: (Data.phrases + Data.sounds).firstOrNull { it.path == itemKey }?.title ?: itemKey, true)

        root.addView(card().apply {
            val info = if (a != null) listOf("אזור" to (a.district ?: "אין מחוז"), "זמן מיגון" to shelter(a.shelter),
                    "קובץ הקראה" to (a.voice?.substringAfterLast('/') ?: "אין"))
                else listOf("קובץ" to itemKey.substringAfterLast('/'))
            info.forEach { (k, v) -> infoRow(this, k, v) }
            val p = itemPath
            if (p != null) {
                divider(this)
                addView(text(if (playing == p) "עצירה" else "השמעה", 15f, BLUE, true).apply {
                    setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { play(p) }
                }.also { playView = it })
            }
        }, lp())

        if (!Review.loggedIn(this)) {
            root.addView(text("כדי לסמן, לכתוב הערה או להציע שינוי - צריך להיכנס במסך הראשי", 13f, MUTED).apply { setPadding(dp(22), dp(4), dp(22), 0) })
            return
        }

        root.addView(label("מצב"))
        root.addView(LinearLayout(this).apply {
            setPadding(dp(12), 0, dp(6), dp(10))
            listOf("" to "לא נבדק", "ok" to "תקין", "fix" to "לתיקון").forEach { (v, t) ->
                val on = statusOf(itemKey) == v
                addView(text(t, 14f, if (on) Color.WHITE else MUTED, on).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11))
                    background = round(if (!on) CARD else when (v) { "ok" -> Color.parseColor("#1E5631"); "fix" -> Color.parseColor("#6B1D1A"); else -> LINE }, 18)
                    setOnClickListener { setStatus(itemKey, v) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            }
        })

        root.addView(label("הערה"))
        root.addView(card().apply {
            val note = EditText(this@MainActivity).apply {
                hint = "למשל: ההגייה של השם לא נכונה"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 15f
                background = null; setText(Review.notes[itemKey] ?: ""); minLines = 2
                setPadding(dp(18), dp(12), dp(18), dp(6))
            }
            addView(note)
            divider(this)
            addView(text("שמירת הערה", 15f, BLUE, true).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                setOnClickListener { sendOp(Review.op("note", itemKey).put("text", note.text.toString().trim()), "ההערה נשמרה") }
            })
        }, lp())

        root.addView(label("הצעת שינוי · ממתינה לאישור, לא מגיעה לטלפונים מכאן"))
        root.addView(card().apply {
            if (a != null) {
                nav(this, R.drawable.ic_edit, "שם", a.name, MUTED) { askText("שם חדש", a.name) { propose("name", a.name, it) } }
                nav(this, R.drawable.ic_map, "אזור", a.district ?: "—", MUTED) {
                    val ds = Data.districts.keys.toList()
                    choose("אזור", ds) { propose("district", a.district ?: "", ds[it]) }
                }
                nav(this, R.drawable.ic_clock, "זמן מיגון", shelter(a.shelter), MUTED) {
                    val vals = listOf(0, 15, 30, 45, 60, 90)
                    choose("זמן מיגון", vals.map { shelter(it) }) { propose("shelter", a.shelter?.toString() ?: "", vals[it].toString()) }
                }
            }
            nav(this, R.drawable.ic_msg, "שינוי אחר", "", MUTED) { askText("מה לשנות?", "") { propose("other", "", it) } }
        }, lp())

        val mine = Review.edits.filter { it.key == itemKey }
        if (mine.isNotEmpty()) {
            root.addView(label("ממתינים"))
            root.addView(card().apply {
                mine.forEach { e ->
                    if (childCount > 0) divider(this)
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text("${FIELD_NAMES[e.field] ?: e.field}: ${e.from.ifEmpty { "—" }} ← ${e.to}", 15f))
                            addView(text(when_(e.at), 12f, MUTED))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(text("ביטול", 14f, RED).apply {
                            setOnClickListener { sendOp(JSONObject().put("op", "cancel").put("id", e.id), "בוטל") }
                        })
                    })
                }
            }, lp())
        }
    }
    private fun label(t: String) = text(t, 13f, MUTED).apply { setPadding(dp(24), dp(6), dp(24), dp(6)) }
    private fun infoRow(c: LinearLayout, k: String, v: String) {
        if (c.childCount > 0) divider(c)
        c.addView(LinearLayout(this).apply {
            setPadding(dp(18), dp(13), dp(18), dp(13))
            addView(text(k, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(v, 14f, MUTED))
        })
    }
    private fun propose(field: String, from: String, to: String) {
        if (to.isBlank() || to == from) return
        sendOp(Review.op("edit", itemKey).put("field", field).put("from", from).put("to", to.trim()), "נשמר כשינוי ממתין")
    }
    private fun askText(title: String, value: String, done: (String) -> Unit) {
        val f = EditText(this).apply { setText(value); setSingleLine(); setSelection(text.length) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) })
            .setPositiveButton("שמירה") { _, _ -> done(f.text.toString().trim()) }.setNegativeButton("ביטול", null).show()
    }
    private fun choose(title: String, options: List<String>, done: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setItems(options.toTypedArray()) { _, i -> done(i) }.setNegativeButton("ביטול", null).show()
    }
}
