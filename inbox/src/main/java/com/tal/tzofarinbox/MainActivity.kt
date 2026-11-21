package com.tal.tzofarinbox

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * פניות צופר: רשימת הפניות ממסך "יצירת קשר" (חדשות מסומנות בנקודה אדומה),
 * ובכל פנייה - ההודעה, פרטי המכשיר, התשובות ושדה תשובה. התשובה מגיעה לפונה כהודעה באפליקציה
 */
class MainActivity : Activity() {
    private object C {
        const val BG = 0xFF000000.toInt(); const val CARD = 0xFF1C1C1E.toInt(); const val CARD2 = 0xFF2C2C2E.toInt()
        const val TEXT = 0xFFFFFFFF.toInt(); const val MUTED = 0xFF8E8E93.toInt(); const val DIV = 0xFF38383A.toInt()
        const val ACCENT = 0xFF0A84FF.toInt(); const val RED = 0xFFFF453A.toInt(); const val GREEN = 0xFF30D158.toInt()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun text(s: String, size: Float, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private lateinit var root: FrameLayout
    private var reports = JSONArray()
    private var openId: String? = null
    private var loading = false
    private var loadError = ""
    private val showInfo = mutableSetOf<String>()
    private var draft = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = C.BG; window.navigationBarColor = C.BG
        root = FrameLayout(this).apply { setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL; fitsSystemWindows = true }
        setContentView(root)
        InboxPush.subscribe()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        openId = intent.getStringExtra("id")
        render()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra("id")?.let { openId = it; draft = "" }
        load()
    }

    override fun onResume() {
        super.onResume()
        InboxPush.onNew = { runOnUiThread { load() } }
        Update.check(this) { runOnUiThread { if (openId == null) render() } }
        load()
    }

    override fun onPause() { super.onPause(); InboxPush.onNew = null }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (openId != null) { openId = null; draft = ""; render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun load() {
        if (!Api.loggedIn(this) || loading) { render(); return }
        loading = true
        Thread {
            val r = Api.list(this)
            runOnUiThread {
                loading = false
                when (r) {
                    is JSONArray -> { reports = r; loadError = "" }
                    "login" -> loadError = ""
                    else -> loadError = "אין חיבור לשרת"
                }
                render()
            }
        }.start()
    }

    private fun find(id: String): JSONObject? = (0 until reports.length()).map { reports.getJSONObject(it) }.firstOrNull { it.optString("id") == id }

    private fun render() {
        root.removeAllViews()
        when {
            !Api.loggedIn(this) -> loginScreen()
            openId != null && find(openId!!) != null -> threadScreen(find(openId!!)!!)
            else -> { if (openId != null && !loading) openId = null; listScreen() }
        }
    }

    // ---- כניסה: קוד האתר ----
    private fun loginScreen() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(20)) }
        col.addView(text("פניות צופר", 26f, bold = true))
        col.addView(text("כניסה עם קוד האתר", 15f, C.MUTED).apply { setPadding(0, dp(6), 0, dp(20)) })
        val input = EditText(this).apply {
            hint = "קוד גישה"; setTextColor(C.TEXT); setHintTextColor(C.MUTED); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = round(C.CARD, 14); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(input, LinearLayout.LayoutParams(-1, -2))
        val err = text("", 14f, C.RED).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(err)
        val btn = text("כניסה", 17f, C.TEXT, true).apply {
            gravity = Gravity.CENTER; background = round(C.ACCENT, 14); setPadding(0, dp(14), 0, dp(14))
        }
        btn.setOnClickListener {
            val code = input.text.toString().trim()
            if (code.isEmpty()) { err.text = "כתוב את הקוד"; return@setOnClickListener }
            btn.isEnabled = false; btn.alpha = 0.6f
            Thread { val r = Api.login(this, code); runOnUiThread { btn.isEnabled = true; btn.alpha = 1f; if (r == null) load() else err.text = r } }.start()
        }
        col.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(col)
    }

    // ---- רשימת הפניות ----
    private fun header(title: String, back: Boolean, extra: View? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(14), dp(16), dp(14))
        if (back) addView(text("→", 22f).apply {
            setPadding(dp(6), 0, dp(12), 0)
            setOnClickListener { openId = null; draft = ""; render() }
        })
        addView(text(title, if (back) 18f else 24f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        extra?.let { addView(it) }
    }

    private fun listScreen() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val unread = (0 until reports.length()).count { !reports.getJSONObject(it).optBoolean("read") }
        val right = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (unread > 0) right.addView(text(if (unread == 1) "חדשה אחת" else "$unread חדשות", 13f, C.RED).apply {
            background = round(0x33FF453A, 20); setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        right.addView(text("↻", 22f, if (loading) C.MUTED else C.TEXT).apply { setPadding(dp(16), 0, 0, 0); setOnClickListener { load() } })
        col.addView(header("פניות", false, right))
        if (Update.status.isNotEmpty()) col.addView(text(Update.status, 13f, C.MUTED).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
        if (loadError.isNotEmpty()) col.addView(text(loadError, 14f, C.RED).apply { setPadding(dp(16), 0, dp(16), dp(8)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        if (reports.length() == 0 && !loading) list.addView(text("אין פניות. כשמישהו ישלח מ\"יצירת קשר\" – היא תופיע כאן ותקבל התראה", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(60), dp(24), 0)
        })
        else {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(C.CARD, 18); clipToOutline = true }
            for (i in 0 until reports.length()) {
                val r = reports.getJSONObject(i)
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(C.DIV) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(34) })
                card.addView(listRow(r))
            }
            list.addView(card)
        }
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col)
    }

    private fun listRow(r: JSONObject): View {
        val read = r.optBoolean("read"); val replied = (r.optJSONArray("replies")?.length() ?: 0) > 0
        val row = LinearLayout(this).apply { gravity = Gravity.TOP; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (read) Color.TRANSPARENT else C.RED) } },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { topMargin = dp(7); marginEnd = dp(11) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this)
        top.addView(text(who(r), 15f, C.TEXT, !read).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(time(r.optLong("at")), 13f, C.MUTED))
        col.addView(top)
        col.addView(text((if (replied) "✓ נענה · " else "") + r.optString("text").replace(Regex("\\s+"), " "), 14f, if (read) C.MUTED else 0xFFC7C7CC.toInt()).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { open(r) }
        return row
    }

    private fun who(r: JSONObject) = (r.optString("m").ifEmpty { r.optString("d") }) + (r.optString("v").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")

    private fun time(at: Long): String {
        val now = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = at }
        val sameDay = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
        if (sameDay(now, t)) return SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(now, t)) return "אתמול " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
        return SimpleDateFormat("d.M HH:mm", Locale.US).format(Date(at))
    }

    private fun open(r: JSONObject) {
        val id = r.optString("id")
        openId = id; draft = ""
        cancelNotif(id)
        if (!r.optBoolean("read")) {
            r.put("read", true)
            Thread { Api.markRead(this, id, true) }.start()
        }
        render()
    }

    // ---- פנייה אחת ----
    private fun threadScreen(r: JSONObject) {
        val id = r.optString("id")
        cancelNotif(id)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(text("לא נקרא", 14f, C.ACCENT).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { r.put("read", false); Thread { Api.markRead(this@MainActivity, id, false) }.start(); openId = null; render() }
        })
        actions.addView(text("מחק", 14f, C.RED).apply {
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity).setMessage("למחוק את הפנייה?")
                    .setPositiveButton("מחק") { _, _ ->
                        Thread {
                            val ok = Api.delete(this@MainActivity, id)
                            runOnUiThread { if (ok == null) toast("המחיקה נכשלה") else { openId = null; load() } }
                        }.start()
                    }.setNegativeButton("ביטול", null).show()
            }
        })
        col.addView(header(who(r), true, actions))

        val msgs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(16)) }
        msgs.addView(bubble(r.optString("text"), r.optLong("at"), mine = false))
        val info = r.optString("info")
        if (info.isNotEmpty()) {
            val shown = id in showInfo
            msgs.addView(text((if (shown) "▾ " else "◂ ") + "פרטי מכשיר", 14f, C.MUTED).apply {
                setPadding(dp(4), dp(10), dp(4), dp(6))
                setOnClickListener { if (shown) showInfo.remove(id) else showInfo.add(id); render() }
            })
            if (shown) msgs.addView(text(info, 13f, 0xFFC7C7CC.toInt()).apply {
                setLineSpacing(0f, 1.25f); background = round(C.CARD, 14); setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true)
            })
        }
        msgs.addView(text("קוד מכשיר: " + r.optString("d"), 12f, C.MUTED).apply { setPadding(dp(4), dp(6), dp(4), dp(4)) })
        val replies = r.optJSONArray("replies") ?: JSONArray()
        for (i in 0 until replies.length()) replies.getJSONObject(i).let { msgs.addView(bubble(it.optString("text"), it.optLong("at"), mine = true)) }
        val scroll = ScrollView(this).apply { addView(msgs) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // שדה התשובה - נשלחת לטלפון של הפונה כהודעה באפליקציה
        val bar = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(10), dp(8), dp(10), dp(10)); setBackgroundColor(C.CARD) }
        val input = EditText(this).apply {
            hint = "תשובה לפונה"; setText(draft); setTextColor(C.TEXT); setHintTextColor(C.MUTED); textSize = 15f
            maxLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(C.CARD2, 20); setPadding(dp(14), dp(10), dp(14), dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { draft = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = text("שלח", 15f, C.TEXT, true).apply {
            gravity = Gravity.CENTER; background = round(C.ACCENT, 20); setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty()) { toast("כתוב תשובה"); return@setOnClickListener }
            send.isEnabled = false; send.alpha = 0.5f
            Thread {
                val res = Api.reply(this, id, t)
                runOnUiThread {
                    send.isEnabled = true; send.alpha = 1f
                    when {
                        res == null -> toast("השליחה נכשלה – בדוק את החיבור")
                        res == "login" -> render()
                        else -> {
                            try { JSONObject(res).optJSONObject("report")?.let { nr -> r.put("replies", nr.optJSONArray("replies")); r.put("read", true) } } catch (_: Exception) { }
                            draft = ""
                            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                            toast("✓ נשלח לפונה")
                            render()
                        }
                    }
                }
            }.start()
        }
        bar.addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(bar)
        root.addView(col)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** הפנייה - בצד ימין באפור; התשובות שלך - בצד שמאל בכחול */
    private fun bubble(s: String, at: Long, mine: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (mine) 0xFF0A3D7A.toInt() else C.CARD, 16); setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        box.addView(text(s, 15f).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) })
        box.addView(text(time(at) + if (mine) " · נשלח" else "", 11f, if (mine) 0xFF9CC3F5.toInt() else C.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        return FrameLayout(this).apply {
            setPadding(0, dp(6), 0, 0)
            addView(box, FrameLayout.LayoutParams(-2, -2, if (mine) Gravity.END else Gravity.START).apply { marginStart = if (mine) dp(48) else 0; marginEnd = if (mine) 0 else dp(48) })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

