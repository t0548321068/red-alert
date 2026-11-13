package com.tal.redalert.editor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.AlertActivity
import com.tal.redalert.AlertPopupActivity
import com.tal.redalert.ClockActivity
import com.tal.redalert.MainActivity
import com.tal.redalert.Prefs
import com.tal.redalert.Shabbat
import org.json.JSONObject

/** עמוד "עריכת צופר": השינויים, שליחה, הדמיית התרעות ומסכים, וגרסה */
@SuppressLint("ViewConstructor")
class Menu(private val layer: Layer) : ScrollView(layer.context) {
    private val ui = Handler(Looper.getMainLooper())
    private val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val footer = Ui.tv(context, "", 12f, Ui.GRAY).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(16), 0, Ui.dp(10)) }
    private var busy = false

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(col)
    }

    private fun card() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = Ui.round(Color.parseColor("#1B1B1D"), 22) }
    private fun addCard(c: View) = col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10)) })
    private fun group(s: String) = col.addView(Ui.tv(context, s, 13f, Color.parseColor("#AAAAAA")).apply { setPadding(Ui.dp(28), Ui.dp(14), Ui.dp(28), Ui.dp(6)) })
    private fun row(c: LinearLayout, title: String, sub: String = "", color: Int = Ui.TEXT, click: (() -> Unit)?): LinearLayout {
        if (c.childCount > 0) c.addView(View(context).apply { setBackgroundColor(Color.parseColor("#2A2A2C")) }, LinearLayout.LayoutParams(-1, 1))
        val r = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(50); setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.tv(context, title, 15f, color))
                if (sub.isNotEmpty()) addView(Ui.tv(context, sub, 12f, Ui.GRAY).apply { setPadding(0, Ui.dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (click != null) setOnClickListener { click() }
        }
        c.addView(r); return r
    }

    fun rebuild() {
        col.removeAllViews()
        val (top, bottom) = layer.insets()
        col.setPadding(0, top, 0, bottom + Ui.dp(30))
        col.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(6))
            addView(Ui.tv(context, "עריכת צופר", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.tv(context, "✕", 22f).apply { setPadding(Ui.dp(12), 0, Ui.dp(4), 0); setOnClickListener { layer.closeMenu() } })
        })
        col.addView(Ui.tv(context, "✎ כחול = מצב עריכה: לוחצים על רכיב ומשנים אותו. ✎ אפור = האפליקציה עובדת רגיל (מעבר בין מסכים). גרירה מזיזה את הכפתור הצף",
            12f, Ui.GRAY).apply { setPadding(Ui.dp(28), 0, Ui.dp(28), Ui.dp(4)) })

        // ---- שינויים ----
        val list = Store.edits.values.filter { it.changed() }
        group("שינויים לשליחה" + if (list.isEmpty()) "" else " (${list.size})")
        val ch = card()
        if (list.isEmpty()) row(ch, "אין שינויים", "מדליקים מצב עריכה (✎) ולוחצים על רכיב", Ui.GRAY, null)
        list.forEach { e ->
            val r = row(ch, e.label, e.screen.substringAfter('|') + (if (e.theme == "light") " · בהיר" else "") + "\n" + describe(e), click = null)
            e.shot?.let { s ->
                try {
                    val bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
                    r.addView(ImageView(context).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)); adjustViewBounds = true; maxHeight = Ui.dp(44); maxWidth = Ui.dp(90) },
                        0, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
                } catch (_: Exception) { }
            }
            r.addView(Ui.tv(context, "מחיקה", 13f, Ui.RED).apply {
                setPadding(Ui.dp(10), Ui.dp(6), 0, Ui.dp(6))
                setOnClickListener { Store.edits.remove(e.key); Store.save(context); rebuild() }
            })
        }
        addCard(ch)
        val crash = Store.crash(context)
        if (crash.isNotEmpty()) col.addView(Ui.tv(context, "הייתה קריסה – היא תישלח עם השינויים", 12f, Ui.ORANGE).apply { gravity = Gravity.CENTER })
        if (!Store.loggedIn(context)) loginBox()
        else col.addView(Ui.btn(context, if (busy) "שולח…" else "שליחה" + if (list.isEmpty()) "" else " (${list.size})",
            if (list.isEmpty() && crash.isEmpty()) Ui.CARD else Color.parseColor("#4C7DFF"), Color.WHITE, true) { send() },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(60), Ui.dp(8), Ui.dp(60), Ui.dp(4)) })
        col.addView(sent)
        loadSent()

        // ---- הדמיית התרעה ----
        group("הדמיית התרעה – סגנון")
        val st = card()
        val styles = listOf(0 to "מסך מלא", 1 to "פופ-אפ", 2 to "כרטיס במסך הראשי", 3 to "ממוזער במסך הראשי")
        st.addView(LinearLayout(context).apply {
            setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10))
            styles.forEachIndexed { i, (k, n) ->
                addView(Ui.tv(context, n, 12f, if (k == demoStyle) Color.WHITE else Ui.TEXT).apply {
                    gravity = Gravity.CENTER; setPadding(Ui.dp(2), Ui.dp(8), Ui.dp(2), Ui.dp(8))
                    background = Ui.round(if (k == demoStyle) Ui.BLUE else Ui.CARD, 10)
                    setOnClickListener { demoStyle = k; rebuild() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = Ui.dp(5) })
            }
        })
        addCard(st)
        group("הדמיית התרעה – סוג (לחיצה מציגה)")
        val ty = card()
        DEMO.forEach { (name, title, level) -> row(ty, name, "", click = { demo(title, level) }) }
        row(ty, "הסרת ההתרעה מהמסך הראשי", "", Ui.RED) { Prefs.setActiveAlert(context, null); toMain() }
        addCard(ty)

        // ---- מסכים נוספים ----
        group("מסכים נוספים")
        val more = card()
        row(more, "שעון לילה", "") { context.startActivity(Intent(context, ClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val sh = Prefs.shabbatMode(context) == Shabbat.ON
        row(more, if (sh) "יציאה ממצב שבת" else "מסך שבת", if (sh) "" else "מצב שבת דלוק עם שעון במסך – יציאה מכאן") {
            if (sh) Prefs.setShabbatMode(context, Shabbat.OFF) else { Prefs.setShabbatClock(context, true); Prefs.setShabbatMode(context, Shabbat.ON) }
            toMain()
        }
        addCard(more)
        col.addView(footer)
        refreshFooter()
    }

    fun refreshFooter() {
        val ver = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "" }
        footer.text = "עריכת צופר ${Update.current(context)} · צופר חירום $ver" + Update.status.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        footer.setOnClickListener {
            if (Update.needPermission) context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else Update.check(context) { Editor.refreshMenus() }
        }
    }

    private fun describe(e: Edit): String {
        val b = e.base; val l = mutableListOf<String>()
        fun f(x: Float) = if (x == x.toInt().toFloat()) x.toInt().toString() else "%.1f".format(x)
        e.color?.let { l += "צבע: ⁦${b.optString("color")}⁩ ← ⁦$it⁩" }
        e.size?.let { l += "גודל: ${b.optString("size")} ← ${f(it)}" }
        e.radius?.let { l += "פינות: ${b.optString("radius")} ← ${f(it)}" }
        e.text?.let { l += "טקסט: \"${b.optString("text")}\" ← \"$it\"" }
        if (e.dx != 0f || e.dy != 0f) l += "מיקום: " + listOfNotNull(
            if (e.dx != 0f) "${f(kotlin.math.abs(e.dx))} " + (if (e.dx > 0) "ימינה" else "שמאלה") else null,
            if (e.dy != 0f) "${f(kotlin.math.abs(e.dy))} " + (if (e.dy > 0) "למטה" else "למעלה") else null).joinToString(", ")
        if (e.note.isNotBlank()) l += "הערה: " + e.note.trim()
        return l.joinToString("\n")
    }

    private fun loginBox() {
        val c = card().apply { setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14)) }
        c.addView(Ui.tv(context, "לשליחה – קוד הכניסה של האתר (כמו בניהול צופר), פעם אחת", 13f, Ui.GRAY))
        val code = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(Ui.TEXT)
            background = Ui.round(Ui.CARD, 10); setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9))
        }
        val err = Ui.tv(context, "", 13f, Ui.RED)
        c.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        c.addView(err)
        c.addView(Ui.btn(context, "כניסה", Color.parseColor("#4C7DFF"), Color.WHITE, true) {
            val s = code.text.toString().trim(); if (s.isEmpty()) return@btn
            err.text = "נכנס…"
            Thread { val r = Store.login(context, s); ui.post { if (r == null) rebuild() else err.text = r } }.start()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        addCard(c)
    }

    private fun send() {
        if (busy) return
        busy = true; rebuild()
        Thread {
            val r = Store.send(context)
            ui.post {
                busy = false
                when {
                    r is JSONObject -> { toast("נשלח ✓ – כתוב לי \"תטפל בעריכה\""); Editor.refreshMenus() }
                    r == "login" -> toast("צריך להיכנס שוב")
                    r == null && Store.count() == 0 -> toast("אין מה לשלוח")
                    else -> toast("השליחה נכשלה – אין חיבור לשרת")
                }
                rebuild()
            }
        }.start()
    }

    private fun loadSent() {
        if (!Store.loggedIn(context)) return
        Thread {
            val r = Store.call(context, null) as? JSONObject ?: return@Thread
            ui.post {
                sent.removeAllViews()
                val a = r.optJSONArray("batches") ?: return@post
                if (a.length() == 0) return@post
                sent.addView(Ui.tv(context, "שליחות קודמות", 13f, Color.parseColor("#AAAAAA")).apply { setPadding(Ui.dp(28), Ui.dp(14), Ui.dp(28), Ui.dp(6)) })
                val c = card()
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
                for (i in a.length() - 1 downTo 0) {
                    val b = a.getJSONObject(i); val n = b.optJSONArray("items")?.length() ?: 0
                    val (stName, stColor) = when (b.optString("state")) { "done" -> "בוצע" to Ui.GREEN; "working" -> "בטיפול" to Ui.ORANGE; else -> "נשלח – ממתין לטיפול" to Color.parseColor("#5AA9FF") }
                    val r2 = row(c, fmt.format(java.util.Date(b.optLong("at"))) + " · " + (if (n == 1) "שינוי אחד" else "$n שינויים"), "", click = null)
                    ((r2.getChildAt(0) as LinearLayout)).addView(Ui.tv(context, stName + b.optString("msg").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(), 12f, stColor))
                }
                sent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10)) })
            }
        }.start()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(context, s, android.widget.Toast.LENGTH_LONG).show()

    /** חזרה למסך הראשי (סוגר את עמודי ההגדרות) */
    private fun toMain() {
        layer.closeMenu()
        MainActivity.shown?.closeAllPages()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** מציג התרעה לדוגמה בסגנון שנבחר - בדיוק כמו התרעה אמיתית (בלי צליל ובלי התראה) */
    private fun demo(title: String, level: Int) {
        val area = Prefs.nearbyAreas(context).firstOrNull() ?: "ירושלים - צפון"
        val now = System.currentTimeMillis()
        val shelter = if (level == 0) 90 else -1
        val j = JSONObject().put("title", title).put("body", area).put("level", level).put("shelter", shelter)
            .put("firedAt", now).put("source", "פיקוד העורף").put("mapAreas", area)
        when (demoStyle) {
            0, 1 -> {
                layer.closeMenu()
                context.startActivity(Intent(context, if (demoStyle == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title", title).putExtra("body", area).putExtra("level", level).putExtra("shelter", shelter)
                    .putExtra("firedAt", now).putExtra("source", "פיקוד העורף").putExtra("mapAreas", area))
            }
            else -> {
                Prefs.setCustomStyles(context, false)
                Prefs.setAlertStyle(context, demoStyle)
                Prefs.setAlertDismissed(context, 0L)
                Prefs.setActiveAlert(context, j)
                toMain()
            }
        }
    }

    companion object {
        var demoStyle = 0
        /** (שם, הכותרת כמו שמגיעה מהמקור, רמה: 0 ירי · 1 מבזק · 2 סיום) */
        val DEMO = listOf(
            Triple("מבזק כללי", "בדקות הקרובות צפויות להתקבל התרעות באזורך", 1),
            Triple("מבזק – איום מלבנון", "התרעה מקדימה - איום מלבנון", 1),
            Triple("ירי רקטות וטילים", "ירי רקטות וטילים", 0),
            Triple("חדירת כלי טיס עוין", "חדירת כלי טיס עוין", 0),
            Triple("חדירת מחבלים", "חדירת מחבלים", 0),
            Triple("רעידת אדמה", "רעידת אדמה", 0),
            Triple("צונאמי", "צונאמי", 0),
            Triple("אירוע רדיולוגי", "אירוע רדיולוגי", 0),
            Triple("אירוע חומרים מסוכנים", "אירוע חומרים מסוכנים", 0),
            Triple("האירוע הסתיים", "האירוע הסתיים", 2),
            Triple("עדכון – הנחיות חזרה למבנים", "רעידת אדמה - הנחיות חזרה למבנים", 2))
    }
}
