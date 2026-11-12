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

    private enum class Page { MAIN, AREAS, DISTRICTS, PHRASES, SOUNDS, ORPHANS, HISTORY, ALERT, ITEM, EDITS, SEND, ADD }
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
    /** סימון נוסף: "נבדק מול רשימת היישובים" - ליישוב ("list:שם") ולאזור ("list-d:שם") */
    private fun listKey(name: String) = "list:$name"
    private fun districtListKey(name: String) = "list-d:$name"
    private fun listChecked(k: String) = statusOf(k) == "ok"
    private fun toggleList(k: String) = setStatus(k, if (listChecked(k)) "" else "ok")
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
    private fun refresh() { if (page == Page.MAIN || page == Page.ITEM) show() else if (page != Page.ADD) refreshList() }

    // ---- השמעה ----
    private var player: MediaPlayer? = null
    private var playing: String? = null
    private var queue: List<String> = emptyList()
    private val tts by lazy { Tts(this) }
    /** עדכון בלי הקלטה - בקול של הטלפון, כמו בצופר חירום */
    private fun say(key: String, text: String) {
        if (playing == key) { stop(); return }
        stop()
        playing = key; refreshList()
        tts.speak(text) { if (playing == key) stop() }
    }
    private fun stop() {
        if (playing != null) tts.stop()
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
    }
    private fun checkUpdate() = Update.check(this) { ui.post { if (page == Page.MAIN) show() } }
    override fun onResume() {
        super.onResume()
        // בכל חזרה לאפליקציה (לא רק בפתיחה ראשונה) - לכל היותר פעם בדקה; מיד אחרי אישור "התקנת עדכונים" בהגדרות
        if (System.currentTimeMillis() - Update.lastCheck > 60_000 || (Update.needPermission && packageManager.canRequestPackageInstalls())) checkUpdate()
    }
    override fun onPause() { stop(); if (recorder != null) stopRec(false); super.onPause() }

    // ---- הקלטת הגייה: אתה מקליט איך השם צריך להישמע, וזה נשמר בשרת (ממנו מכינים הקלטה חדשה בקול אבר) ----
    private var recorder: android.media.MediaRecorder? = null
    private var recStarted = 0L
    private var recBusy = false
    private fun recFile() = java.io.File(cacheDir, "my_rec.m4a")
    private val recTick = object : Runnable {
        override fun run() { if (recorder != null) { recView?.text = "עצירה ושמירה · " + ((System.currentTimeMillis() - recStarted) / 1000) + " שנ׳"; ui.postDelayed(this, 500) } }
    }
    private var recView: TextView? = null
    private fun startRec() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7); return
        }
        stop()
        try {
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(96000); r.setAudioChannels(1)
            r.setMaxDuration(30_000)
            r.setOnInfoListener { _, what, _ -> if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ui.post { stopRec(true) } }
            r.setOutputFile(recFile().path)
            r.prepare(); r.start()
            recorder = r; recStarted = System.currentTimeMillis()
            item(); ui.post(recTick)
        } catch (_: Exception) { recorder = null; toast("ההקלטה לא התחילה") }
    }
    private fun stopRec(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        val ok = try { r.stop(); true } catch (_: Exception) { false }
        r.release()
        if (!save || !ok || System.currentTimeMillis() - recStarted < 600) { if (save) toast("ההקלטה קצרה מדי"); if (page == Page.ITEM) item(); return }
        val key = itemKey
        recBusy = true; if (page == Page.ITEM) item()
        Thread {
            val done = Review.upload(this, key, recFile())
            ui.post { recBusy = false; toast(if (done) "ההקלטה נשמרה" else "לא נשמר – אין חיבור לשרת"); if (page == Page.ITEM) item() }
        }.start()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7) { if (res.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startRec() else toast("בלי הרשאת מיקרופון אי אפשר להקליט") }
    }
    /** השמעת ההקלטה שלך (או הגרסה בקול אבר - cand) מהשרת */
    private fun playMine(key: String, cand: Boolean = false) {
        val k = (if (cand) "cand:" else "mine:") + key
        if (playing == k) { stop(); return }
        stop()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        try {
            mp.setDataSource(this, Uri.parse(if (cand) Review.candUrl(key) else Review.recUrl(key)), Review.cookie(this))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) { stop(); toast("ההקלטה לא נפתחה") }; true }
            player = mp; playing = k; mp.prepareAsync()
        } catch (_: Exception) { mp.release(); toast("ההקלטה לא נפתחה") }
        if (page == Page.ITEM) item()
    }

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
    // מיקום הגלילה של כל רשימה במחסנית - כדי לחזור בדיוק לאותו מקום אחרי "חזרה"
    private val scrolls = ArrayDeque<Pair<Int, Int>>()
    private var listView: ListView? = null
    private var mainScroll: ScrollView? = null
    private var itemScroll: ScrollView? = null
    private fun scrollPos(): Pair<Int, Int> = when (page) {
        Page.MAIN -> (mainScroll?.scrollY ?: 0) to -1
        Page.ITEM -> 0 to 0
        else -> listView?.let { lv -> lv.firstVisiblePosition to (lv.getChildAt(0)?.top ?: 0) } ?: (0 to 0)
    }
    private fun go(p: Page) { stop(); stack.addLast(page); scrolls.addLast(scrollPos()); page = p; checkAreas = false; show() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.isEmpty()) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        stop()
        page = stack.removeLast()
        val pos = scrolls.removeLastOrNull() ?: (0 to 0)
        if (page == Page.MAIN) { query = ""; histQuery = ""; district = null }
        show()
        when (page) {
            Page.MAIN -> Unit   // main() שומר את הגלילה בעצמו
            Page.ITEM -> Unit
            else -> listView?.setSelectionFromTop(pos.first, pos.second)
        }
    }
    private fun show() { when (page) { Page.MAIN -> main(); Page.ITEM -> item(); Page.ADD -> addPage(); else -> list() } }

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
        // רענון המסך (עדכון, סימון) - נשארים באותו מקום בגלילה
        val y = mainScroll?.scrollY ?: 0
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root); post { scrollTo(0, y) } }.also { mainScroll = it })
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

        if (!Review.loggedIn(this)) loginCard(root) else sendCard(root)

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
                val inList = a.count { listChecked(listKey(it.name)) }
                val dList = Data.districts.keys.count { listChecked(districtListKey(it)) }
                nav(this, R.drawable.ic_listcheck, "יישובים שנבדקו ברשימה", "${n.format(inList)}/${n.format(a.size)}", if (inList == a.size) BLUE else Color.WHITE) { areasWith(6) }
                nav(this, R.drawable.ic_listcheck, "אזורים שנבדקו ברשימה", "$dList/${Data.districts.size}", if (dList == Data.districts.size) BLUE else Color.WHITE) { go(Page.DISTRICTS) }
                val ready = Review.recs.values.count { it.state == "ready" }
                nav(this, R.drawable.ic_mic, "הקלטות הגייה שלך", Review.recs.size.toString() + (if (ready > 0) " · $ready מוכנות לאישור" else ""),
                    if (ready > 0) GREEN else if (Review.recs.isEmpty()) MUTED else Color.WHITE, Review.recs.isNotEmpty()) { areasWith(7) }
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
        root.addView(text("גרסה ${Update.current(this)}" + when {
                Update.busy -> " · בודק עדכונים…"
                Update.latestSeen.isNotEmpty() -> " · מעודכן · בדיקה אחרונה ${ago(Update.lastCheck)}"
                else -> " · בדיקת עדכונים"
            }, 12f, MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { checkUpdate() }
            }, LinearLayout.LayoutParams(-1, -2))
    }
    /** שליחה לטיפול: כל מה שמחכה (הקלטות, הצעות שינוי, לתיקון) נשלח, ואני מכניס את השינויים */
    private fun sendCard(root: LinearLayout) {
        val n = Review.waiting(); val b = Review.lastBatch
        root.addView(card().apply {
            setPadding(0, dp(4), 0, dp(14))
            val (line, color) = when {
                b?.state == "sent" -> "נשלח לטיפול ב-${when_(b.at)} · ממתין שאכניס" to ORANGE
                b?.state == "done" -> "טופל ב-${when_(b.doneAt)}" + (if (b.summary.isNotEmpty()) " · ${b.summary}" else "") to GREEN
                else -> "עוד לא נשלח לטיפול" to MUTED
            }
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(14), dp(10))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (n == 0) "אין שינויים שמחכים" else "$n שינויים מחכים לשליחה", 15f, bold = true))
                    addView(text(line, 12f, color))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(icon(R.drawable.ic_chev, MUTED, 18))
                setOnClickListener { go(Page.SEND) }   // מה יש בשליחה
            })
            addView(text("שליחה לטיפול", 16f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; includeFontPadding = false
                background = round(if (n > 0) Color.parseColor("#4C7DFF") else LINE, 23)
                setOnClickListener {
                    if (n == 0) { toast("אין מה לשלוח"); return@setOnClickListener }
                    sendOp(JSONObject().put("op", "submit"), "נשלח לטיפול")
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(40), dp(4), dp(40), 0) })
        }, lp())
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
        val seq: List<String>? = null, val tap: (() -> Unit)? = null, val chevron: Boolean = false, val header: Boolean = false,
        val ok: Boolean = false, val say: String? = null, val found: Boolean? = null, val listKey: String? = null, val sel: String? = null,
        val badge: Pair<String, Int>? = null)
    /** בשליחה לטיפול: מה לא לשלוח (ברירת מחדל - הכל מסומן) */
    private val unselected = HashSet<String>()
    private fun submitSelected() {
        val recs = Review.recs.filter { (it.value.state == "pending" || it.value.state == "rejected") && "rec:${it.key}" !in unselected && !Review.inFlight("rec", it.key) }.keys
        val edits = Review.edits.map { it.id }.filter { "edit:$it" !in unselected && !Review.inFlight("edit", it) }
        val fix = Review.status.filter { it.value == "fix" && "fix:${it.key}" !in unselected && !Review.inFlight("fix", it.key) }.keys
        val tasks = Review.tasks.map { it.id }.filter { "task:$it" !in unselected }
        if (recs.isEmpty() && edits.isEmpty() && fix.isEmpty() && tasks.isEmpty()) { toast("לא סומן כלום לשליחה"); return }
        sendOp(JSONObject().put("op", "submit").put("recs", org.json.JSONArray(recs.toList())).put("edits", org.json.JSONArray(edits))
            .put("fix", org.json.JSONArray(fix.toList())).put("tasks", org.json.JSONArray(tasks)), "נשלח לטיפול")
    }
    private var items: List<Item> = emptyList()
    private var adapter: BaseAdapter? = null
    private var countView: TextView? = null
    private var allDot: CheckDot? = null
    private var selCount: TextView? = null

    private fun list() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        header(root, when (page) {
            Page.AREAS -> district ?: "יישובים"; Page.DISTRICTS -> "אזורים"; Page.PHRASES -> "משפטי הקראה"
            Page.SOUNDS -> "צלילי התרעה"; Page.HISTORY -> "היסטוריית התרעות"; Page.EDITS -> "שינויים ממתינים"; Page.SEND -> "שליחה לטיפול"
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
            listOf("הכל", "חסרים", "לא נבדקו", "תקינים", "לתיקון", "נבדקו ברשימה", "לא נבדקו ברשימה", "הקלטות שלי").forEachIndexed { i, t ->
                chips.addView(text(t, 13f).apply {
                    background = round(if (i == filter) BLUE else LINE, 16); setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { filter = i; list() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }

        if (page == Page.SEND) {
            // [עיגול "הכול"] "N נבחרו" - כמו בבחירת יישובים בצופר חירום
            val dot = CheckDot(this, false); allDot = dot
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(20), dp(10))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(text("הכול", 12f).apply { setPadding(0, dp(2), 0, 0) })
                    setOnClickListener {
                        val ids = items.mapNotNull { it.sel }
                        if (ids.isNotEmpty() && ids.none { it in unselected }) unselected.addAll(ids) else unselected.removeAll(ids.toSet())
                        refreshList()
                    }
                }, LinearLayout.LayoutParams(dp(44), -2))
                addView(text("", 22f, bold = true).also { selCount = it }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            })
        } else { allDot = null; selCount = null }
        val count = text("", 12f, MUTED).apply { setPadding(dp(22), 0, dp(22), dp(6)) }
        root.addView(count)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup) =
                if (items[p].header) text(items[p].title, 13f, MUTED).apply { setPadding(dp(24), dp(if (p == 0) 2 else 14), dp(24), dp(6)) }
                else row(items[p], p == 0 || items[p - 1].header, p == items.size - 1 || items[p + 1].header)
        }
        root.addView(ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, dp(24)); clipToPadding = false
            adapter = this@MainActivity.adapter
        }.also { listView = it }, LinearLayout.LayoutParams(-1, 0, 1f))
        countView = count
        if (page == Page.SEND) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(18))
                addView(text("הוספה", 16f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false; background = round(LINE, 23)
                    setOnClickListener { go(Page.ADD) }
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
                addView(text("שליחה לטיפול", 16f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    background = round(Color.parseColor("#4C7DFF"), 23)
                    setOnClickListener { submitSelected() }
                }, LinearLayout.LayoutParams(0, dp(46), 1.6f).apply { marginStart = dp(10) })
            })
        }
        if (page == Page.ALERT) {
            // כפתורים כחולים למטה (כמו "בדוק אם יש עדכונים" בצופר חירום)
            fun pill(label: String, click: () -> Unit) = text(label, 16f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; includeFontPadding = false
                background = round(Color.parseColor("#4C7DFF"), 23); setOnClickListener { click() }
            }
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(18))
                addView(pill("בדוק יישובים") { checkAreas = !checkAreas; refreshList() }, LinearLayout.LayoutParams(0, dp(46), 1f))
                addView(pill("הצג התרעה") {
                    alert?.let { Data.simAlert = it; stop(); startActivity(Intent(this@MainActivity, SimActivity::class.java)) }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
            })
        }
        refreshList()
    }
    /** בדף התרעה: בדיקה שכל היישובים שבה מופיעים ברשימת היישובים */
    private var checkAreas = false

    private fun shelter(s: Int?) = when (s) { null -> "אין זמן מיגון"; 0 -> "מיידי"; 90 -> "דקה וחצי"; 60 -> "דקה"; else -> "$s שנ׳" }
    private val FIELD_NAMES = mapOf("name" to "שם", "district" to "אזור", "shelter" to "זמן מיגון", "other" to "אחר")
    private fun note(k: String) = Review.notes[k]?.let { "\n✎ $it" } ?: ""

    private fun refreshList() {
        if (page == Page.MAIN || page == Page.ITEM) return
        items = when (page) {
            Page.AREAS -> Data.areas.filter {
                (district == null || it.district == district) &&
                (query.isEmpty() || it.name.contains(query)) &&
                    when (filter) { 1 -> it.missing; 2 -> statusOf(it.name) == ""; 3 -> statusOf(it.name) == "ok"; 4 -> statusOf(it.name) == "fix"
                        5 -> listChecked(listKey(it.name)); 6 -> !listChecked(listKey(it.name)); 7 -> Review.recs.containsKey(it.name); else -> true }
            }.map { a ->
                val sub = listOf(a.district ?: "אין מחוז", shelter(a.shelter)).joinToString(" · ") +
                    (if (!a.voiceOk) " · אין הקראה" else "") + (a.voice?.let { "\n" + it.substringAfterLast('/') } ?: "") +
                    (Review.recs[a.name]?.let { r -> " · הקלטה שלך: " + when (r.state) { "ready" -> "מוכן לאישור"; "approved" -> "אושר"; "rejected" -> "נדחה"; else -> "ממתין" } } ?: "") + note(a.name)
                Item(a.name, sub, a.voice?.takeIf { a.voiceOk }, a.name, a.missing, tap = { openItem(a.name, a.voice?.takeIf { a.voiceOk }, a) },
                    listKey = listKey(a.name))
            }
            Page.DISTRICTS -> Data.districts.map { (d, list) ->
                val ok = list.count { statusOf(it.name) == "ok" }; val fix = list.count { statusOf(it.name) == "fix" }
                val miss = list.count { it.missing }
                val inl = list.count { listChecked(listKey(it.name)) }
                Item(d, "${list.size} יישובים" + (if (Review.loggedIn(this)) " · נבדקו $ok · ברשימה $inl/${list.size}" else "") +
                    (if (fix > 0) " · לתיקון $fix" else "") + (if (miss > 0) " · חסרים $miss" else ""),
                    null, "district:$d", miss > 0, tap = { district = d; filter = 0; query = ""; go(Page.AREAS) }, chevron = true,
                    listKey = districtListKey(d))
            }
            Page.PHRASES -> Data.phrases.groupBy { it.group }.flatMap { (g, list) ->
                listOf(Item(g, "", null, null, false, header = true)) + list.map {
                    Item(it.title, it.path.removePrefix("voice/") + note(it.path), it.path, it.path, false, tap = { openItem(it.path, it.path, null) })
                }
            }
            Page.SOUNDS -> Data.sounds.map {
                Item(it.title, it.path.substringAfterLast('/') + note(it.path), it.path, it.path, false, tap = { openItem(it.path, it.path, null) })
            }
            Page.SEND -> {
                fun title(k: String) = when {
                    k.startsWith("district:") -> "אזור " + k.removePrefix("district:")
                    k.startsWith("voice/") || k.startsWith("sounds/") -> (Data.phrases + Data.sounds).firstOrNull { it.path == k }?.title ?: k
                    else -> k
                }
                fun open(k: String) { val a = Data.areas.firstOrNull { it.name == k }
                    if (a != null) openItem(k, a.voice?.takeIf { a.voiceOk }, a)
                    else if (k.startsWith("voice/") || k.startsWith("sounds/")) openItem(k, k, null) }
                val recs = Review.recs.filter { (it.value.state == "pending" || it.value.state == "rejected") && !Review.inFlight("rec", it.key) }
                val fix = Review.status.filter { it.value == "fix" && !Review.inFlight("fix", it.key) }.keys
                val editsW = Review.edits.filter { !Review.inFlight("edit", it.id) }
                val out = mutableListOf<Item>()
                // מה שכבר נשלח - עם סטטוס לכל פריט (5 השליחות האחרונות)
                Review.batches.take(5).forEach { b ->
                    val done = b.items.count { it.state == "done" || it.state == "skipped" }
                    out += Item("נשלח ${when_(b.at)} · ${if (b.state == "done") "הסתיים" else "טופלו $done מתוך ${b.items.size}"}", "", null, null, false, header = true)
                    b.items.forEach { i ->
                        val badge = when (i.state) { "working" -> "בטיפול" to BLUE; "done" -> "טופל" to GREEN; "skipped" -> "לא בוצע" to RED; else -> "נשלח" to ORANGE }
                        val key = if (i.t == "edit") i.title else i.k
                        out += Item(title(i.title), i.detail.let { d -> if (i.t == "edit") d.replace(Regex("^(\\w+):")) { m -> (FIELD_NAMES[m.groupValues[1]] ?: m.groupValues[1]) + ":" } else d } +
                            (if (i.msg.isNotEmpty()) "\n" + i.msg else ""), null, null, false, badge = badge,
                            tap = if (i.t == "task") null else ({ open(key) }))
                    }
                }
                if (recs.isNotEmpty()) {
                    out += Item("הקלטות הגייה · ${recs.size}", "", null, null, false, header = true)
                    recs.forEach { (k, r) -> out += Item(title(k), (if (r.state == "rejected") "נדחה – גרסה חדשה בקול אבר" else "גרסה בקול אבר") + " · הוקלט ${when_(r.at)}",
                        null, null, false, chevron = true, tap = { open(k) }, sel = "rec:$k") }
                }
                if (recs.isNotEmpty() || editsW.isNotEmpty() || fix.isNotEmpty() || Review.tasks.isNotEmpty())
                    out += Item("מחכה לשליחה", "", null, null, false, header = true)
                if (editsW.isNotEmpty()) {
                    out += Item("הצעות שינוי · ${editsW.size}", "", null, null, false, header = true)
                    editsW.forEach { e -> out += Item(title(e.key), "${FIELD_NAMES[e.field] ?: e.field}: ${e.from.ifEmpty { "—" }} ← ${e.to}",
                        null, null, false, chevron = true, tap = { open(e.key) }, sel = "edit:${e.id}") }
                }
                if (fix.isNotEmpty()) {
                    out += Item("לתיקון · ${fix.size}", "", null, null, false, header = true)
                    fix.forEach { k -> out += Item(title(k), Review.notes[k] ?: "בלי הערה", null, null, Review.notes[k] == null,
                        chevron = true, tap = { open(k) }, sel = "fix:$k") }
                }
                if (Review.tasks.isNotEmpty()) {
                    out += Item("נוסף ידנית · ${Review.tasks.size}", "", null, null, false, header = true)
                    Review.tasks.forEach { t -> out += Item(t.text, when_(t.at) + " · לחיצה למחיקה", null, null, false, sel = "task:${t.id}", tap = {
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("למחוק?").setMessage(t.text)
                            .setPositiveButton("מחיקה") { _, _ -> sendOp(JSONObject().put("op", "deltask").put("id", t.id), "נמחק") }
                            .setNegativeButton("ביטול", null).show() }, chevron = true) }
                }
                out
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
                val c = Data.clips(a)
                Item(a.title, when_(a.ts) + " · " + names, "alert:${a.ts}:${a.raw}", null, missing,
                    seq = c ?: emptyList(), say = if (c == null) Tts.textFor(a.raw, a.areas) else null, tap = { alert = a; go(Page.ALERT) })
            }
            Page.ALERT -> alert?.let { a ->
                val c = Data.clips(a)
                val notFound = a.areas.filter { Data.match(it).isEmpty() }
                val check = if (!checkAreas) emptyList() else listOf(
                    if (notFound.isEmpty()) Item("כל ${a.areas.size} היישובים מופיעים ברשימה", "נבדק מול רשימת היישובים של צופר חירום", null, null, false, chevron = false, header = false, ok = true)
                    else Item("${notFound.size} מתוך ${a.areas.size} לא מופיעים ברשימה", notFound.joinToString(", "), null, null, true))
                check + listOf(Item("הקראה מלאה", when_(a.ts) + " · " + a.areas.size + " יישובים" +
                    (if (c == null) "\nעדכון - מוקרא בקול של הטלפון, כמו באפליקציה" else ""),
                    "alert:${a.ts}:${a.raw}", null, false, seq = c ?: emptyList(), say = if (c == null) Tts.textFor(a.raw, a.areas) else null)) +
                a.areas.map { r ->
                    val m = Data.match(r); val v = m.firstOrNull { it.voiceOk }
                    val sub = if (m.isEmpty()) "לא נמצא ברשימת היישובים" else if (v == null) "אין הקראה"
                        else listOfNotNull(v.district, v.voice?.substringAfterLast('/')).joinToString(" · ")
                    val area = m.firstOrNull()
                    Item(r, sub, v?.voice, area?.name, v == null, tap = area?.let { ar -> { openItem(ar.name, v?.voice, ar) } },
                        found = if (checkAreas) m.isNotEmpty() else null)
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
            page == Page.DISTRICTS -> "${items.size} אזורים" + (if (Review.loggedIn(this))
                " · נבדקו מול הרשימה ${Data.districts.keys.count { listChecked(districtListKey(it)) }}/${Data.districts.size}" else "")
            page == Page.AREAS && Review.loggedIn(this) -> "${items.size} יישובים · נבדקו מול הרשימה ${items.count { it.listKey != null && listChecked(it.listKey) }}/${items.size}"
            page == Page.PHRASES -> "${items.count { !it.header }} הקלטות"
            page == Page.SEND -> Review.lastBatch.let { b -> when {
                items.isEmpty() -> "אין שינויים שמחכים"
                b?.state == "sent" -> "נשלח לטיפול ב-${when_(b.at)} · ממתין שאכניס"
                else -> items.filter { it.sel != null }.let { s -> "מתוך ${s.size} שמחכים לשליחה" } } }
            else -> "${items.size} פריטים"
        }
        if (page == Page.SEND) {
            val ids = items.mapNotNull { it.sel }; val n = ids.count { it !in unselected }
            allDot?.let { it.on = ids.isNotEmpty() && n == ids.size; it.invalidate() }
            selCount?.text = when { ids.isEmpty() -> "אין מה לשלוח"; n == 1 -> "1 נבחר"; else -> "$n נבחרו" }
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
                addView(text(it.title, 15f, if (it.ok) GREEN else if (page == Page.ALERT && it.bad && it.path == null && it.key == null && it.seq == null) RED
                    else if (it.title.startsWith("התרעה מקדימה")) ORANGE
                    else if (page == Page.HISTORY && it.title == "האירוע הסתיים") GREEN else Color.WHITE))
                addView(text(it.sub, 12f, if (it.bad) ORANGE else MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // בשליחה לטיפול: מה לשלוח
            it.sel?.let { sid ->
                addView(CheckDot(this@MainActivity, sid !in unselected).apply {
                    setOnClickListener { if (!unselected.remove(sid)) unselected.add(sid); refreshList() }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            }
            // "בדוק יישובים": וי ירוק - מופיע ברשימת היישובים, סימן אדום - לא מופיע
            it.found?.let { f -> addView(icon(if (f) R.drawable.ic_ok else R.drawable.ic_warn, if (f) GREEN else RED, 22).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) }) }
            if (it.found == null && Review.loggedIn(this@MainActivity)) it.listKey?.let { lk ->
                val on = listChecked(lk)
                addView(icon(R.drawable.ic_listcheck, if (on) BLUE else Color.parseColor("#48484A"), 22).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
                    setOnClickListener { toggleList(lk) }
                })
            }
            if (it.found == null) it.key?.let { k ->
                if (Review.loggedIn(this@MainActivity)) addView(statusIcon(k).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
                    setOnClickListener { cycle(k) }
                })
            }
            if (it.badge != null) addView(text(it.badge.first, 13f, it.badge.second, true).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = round(Color.argb(45, Color.red(it.badge.second), Color.green(it.badge.second), Color.blue(it.badge.second)), 12)
            })
            else if (it.chevron) addView(icon(R.drawable.ic_chev, MUTED, 18))
            else if (it.ok) addView(icon(R.drawable.ic_ok, GREEN, 24))
            else if (page == Page.ALERT && it.path == null && it.key == null && it.seq == null) addView(icon(R.drawable.ic_warn, RED, 24))
            else {
                val path = it.path; val seq = it.seq; val sayText = it.say
                addView(FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LINE) }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(if (path != null && playing == path) R.drawable.ic_stop else R.drawable.ic_play)
                        setColorFilter(if (path == null) MUTED else Color.WHITE)
                    }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    if (path != null) setOnClickListener { if (sayText != null) say(path, sayText) else if (seq != null) play(path, seq) else play(path) }
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
        val y = if (itemScroll?.tag == itemKey) itemScroll?.scrollY ?: 0 else 0   // רענון אחרי סימון / שמירה - באותו מקום
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root); tag = itemKey; post { scrollTo(0, y) } }.also { itemScroll = it })
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

        if (a != null) {
            val lk = listKey(a.name); val on = listChecked(lk)
            root.addView(card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
                    addView(icon(R.drawable.ic_listcheck, if (on) BLUE else MUTED, 20).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
                    addView(text("נבדק מול רשימת היישובים", 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(if (on) "כן" else "לא", 14f, if (on) BLUE else MUTED))
                    setOnClickListener { toggleList(lk) }
                })
            }, lp())
        }
        root.addView(label("ההגייה שלך · מקליטים איך השם צריך להישמע, ומכינים ממנו הקלטה בקול אבר"))
        root.addView(card().apply {
            val has = Review.recs[itemKey]
            when {
                recorder != null -> addView(text("", 15f, RED, true).apply {
                    setPadding(dp(18), dp(14), dp(18), dp(14)); recView = this
                    text = "עצירה ושמירה · " + ((System.currentTimeMillis() - recStarted) / 1000) + " שנ׳"
                    setOnClickListener { stopRec(true) }
                })
                recBusy -> addView(text("שומר…", 15f, MUTED).apply { setPadding(dp(18), dp(14), dp(18), dp(14)) })
                has != null -> {
                    infoRow(this, "הוקלט", when_(has.at))
                    divider(this)
                    val (st, stColor) = when (has.state) {
                        "ready" -> "מוכן לאישור" to GREEN; "approved" -> "אושר – נכנס לטלפונים" to BLUE
                        "rejected" -> "נדחה – אכין גרסה חדשה" to RED; else -> "ממתין – אכין גרסה בקול אבר" to ORANGE
                    }
                    addView(LinearLayout(this@MainActivity).apply {
                        setPadding(dp(18), dp(13), dp(18), dp(13))
                        addView(text("מצב", 15f), LinearLayout.LayoutParams(0, -2, 1f)); addView(text(st, 14f, stColor, true))
                    })
                    if (has.state != "pending") {
                        divider(this)
                        addView(text(if (playing == "cand:$itemKey") "עצירה" else "השמעת הגרסה בקול אבר", 15f, BLUE, true).apply {
                            setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { playMine(itemKey, true) } })
                    }
                    if (has.state == "ready") {
                        divider(this)
                        addView(LinearLayout(this@MainActivity).apply {
                            setPadding(dp(12), dp(10), dp(12), dp(12))
                            addView(text("אישור", 15f, Color.WHITE, true).apply {
                                gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11)); background = round(Color.parseColor("#1E5631"), 18)
                                setOnClickListener { sendOp(Review.op("approve", itemKey), "אושר – אכניס את הגרסה החדשה") }
                            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                            addView(text("לא טוב", 15f, Color.WHITE, true).apply {
                                gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11)); background = round(Color.parseColor("#6B1D1A"), 18)
                                setOnClickListener { sendOp(Review.op("reject", itemKey), "נדחה – אכין גרסה חדשה") }
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        })
                    }
                    divider(this)
                    addView(text(if (playing == "mine:$itemKey") "עצירה" else "השמעת ההקלטה שלך", 15f, BLUE, true).apply {
                        setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { playMine(itemKey) } })
                    divider(this)
                    addView(text("הקלטה מחדש", 15f, BLUE, true).apply { setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { startRec() } })
                    divider(this)
                    addView(text("מחיקת ההקלטה", 15f, RED).apply {
                        setPadding(dp(18), dp(14), dp(18), dp(14))
                        setOnClickListener { sendOp(Review.op("delrec", itemKey), "ההקלטה נמחקה") }
                    })
                }
                else -> addView(text("הקלטה", 15f, BLUE, true).apply { setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { startRec() } })
            }
        }, lp())

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
    // ---- הוספה ידנית לטיפול: כרטיס עם סוג, על מה, ומה לעשות ----
    private var addKind = "יישוב"
    private fun addPage() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
        header(root, "הוספה לטיפול", true)
        root.addView(label("סוג"))
        val chips = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(10)) }
        listOf("יישוב", "אזור", "הקראה", "צליל", "אחר").forEach { k ->
            chips.addView(text(k, 14f).apply {
                background = round(if (k == addKind) BLUE else LINE, 16); setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { addKind = k; addPage() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        fun field(hint: String, lines: Int) = EditText(this).apply {
            this.hint = hint; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 16f; background = null
            minLines = lines; if (lines == 1) setSingleLine(); setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(label("על מה"))
        val subject = field(when (addKind) { "יישוב" -> "שם היישוב"; "אזור" -> "שם האזור"; "הקראה" -> "איזה משפט / הקלטה"; "צליל" -> "איזה צליל"; else -> "נושא" }, 1)
        root.addView(card().apply { addView(subject) }, lp())
        root.addView(label("מה לעשות"))
        val desc = field("למשל: להוסיף יישוב חדש, לתקן את השם, ההגייה לא נכונה…", 4)
        root.addView(card().apply { addView(desc) }, lp())
        val err = text("", 13f, RED).apply { setPadding(dp(24), 0, dp(24), 0) }
        root.addView(err)
        root.addView(text("הוספה", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; background = round(Color.parseColor("#4C7DFF"), 23)
            setOnClickListener {
                val s = subject.text.toString().trim(); val d = desc.text.toString().trim()
                if (d.isEmpty()) { err.text = "כתוב מה לעשות"; return@setOnClickListener }
                val t = "$addKind" + (if (s.isNotEmpty()) " · $s" else "") + " – $d"
                sendOp(JSONObject().put("op", "task").put("text", t), "נוסף") { if (page == Page.ADD) onBackPressed() }
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(40), dp(16), dp(40), 0) })
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
