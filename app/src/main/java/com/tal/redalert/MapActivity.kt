package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/** מפת ההתראות - אזורים שהותרעו, צבועים לפי סוג ההתראה האחרונה */
class MapActivity : Activity() {
    private var dark = false
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Prefs.darkTheme(this)
        window.decorView.setBackgroundColor(Color.parseColor(if (dark) "#000000" else "#F2F2F7"))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        // המפה לא נכנסת מתחת לשורת המצב ולשורת הניווט של הטלפון
        val bg = Color.parseColor(if (dark) "#000000" else "#F2F2F7")
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        // אייקונים כהים בשורות כשהרקע בהיר
        if (!dark && android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(frame)
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun locate() = runOnUiThread { locate(web) }
        }, "App")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Thread {
                    val data = buildData(dark)
                    runOnUiThread {
                        view.evaluateJavascript("render($data);", null)
                        // נפתח מהתראה ברשימה - מתמקדים באזורים שלה
                        intent.getStringExtra("focus")?.let { body ->
                            val names = JSONArray()
                            body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach { raw -> AreaData.match(this@MapActivity, raw).forEach { names.put(it) } }
                            view.evaluateJavascript("focusOn($names, ${intent.getLongExtra("focusTs", 0L)}, " +
                                "${intent.getBooleanExtra("focusAll", false)}, ${intent.getIntExtra("focusLevel", -1)}, " +
                                "${org.json.JSONObject.quote(intent.getStringExtra("focusTitle") ?: "")});", null)
                        }
                        locate(view)
                        // התרעות שמגיעות בזמן שהמפה פתוחה
                        lastFeed = Prefs.feed(this@MapActivity).hashCode()
                        live.removeCallbacks(liveTick); live.postDelayed(liveTick, 2000)
                    }
                }.start()
            }
        }
        web.loadUrl("file:///android_asset/map.html" + if (dark) "" else "#light")   // ערכת נושא מהרגע הראשון
    }

    private val live = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastFeed = 0
    /** כל 2 שניות: אם נוספה התרעה לפיד - שולחים למפה (היא מתקרבת לאזורים החדשים ואז מתרחקת) */
    private val liveTick: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val h = Prefs.feed(this@MapActivity).hashCode()
            if (h != lastFeed) {
                lastFeed = h
                Thread {
                    val data = buildData(dark)
                    runOnUiThread { if (!isFinishing) web.evaluateJavascript("update($data);", null) }
                }.start()
            }
            live.postDelayed(this, 2000)
        }
    }

    override fun onDestroy() { live.removeCallbacks(liveTick); super.onDestroy() }

    /** מיקום עדכני ומדויק (לא רק האחרון שהטלפון זוכר) - מסומן על המפה עם עיגול דיוק */
    private fun locate(view: WebView) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        // קודם המיקום האחרון (מיידי), ואז מיקום עדכני
        Weather.lastLocation(this)?.let { show(view, it) }
        Weather.freshLocation(this) { loc -> if (loc != null && !isFinishing) show(view, loc) }
    }

    private fun show(view: WebView, l: android.location.Location) {
        view.evaluateJavascript("setMe(${l.latitude}, ${l.longitude}, ${l.accuracy});", null)
    }

    /** ממיר רשימת התראות לנתונים למפה (שמות האזורים המלאים) */
    private fun events(list: List<Prefs.Entry>): JSONArray {
        val events = JSONArray()
        list.filter { it.ts > 0 }.forEach { e ->
            val names = JSONArray()
            e.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
                AreaData.match(this, raw).forEach { name -> names.put(name) }
            }
            if (names.length() > 0) {
                events.put(JSONObject()
                    .put("ts", e.ts).put("title", e.title)
                    .put("level", AlertUi.shownLevel(e.title, e.level)).put("areas", names))   // "איום" - בכתום
            }
        }
        return events
    }

    /**
     * כל הארץ, ו"שלי" = ההתראות שקיבלתי + התראות בארץ באזורים שהייתי בהם באותו זמן
     * (לפי היסטוריית המיקום)
     */
    private fun buildData(dark: Boolean): String {
        val visits = Prefs.visits(this)
        val all = events(Prefs.feed(this))
        val mine = events(Prefs.history(this))
        for (i in 0 until all.length()) {
            val e = all.getJSONObject(i)
            val ts = e.getLong("ts")
            val areas = e.getJSONArray("areas")
            val here = JSONArray()
            for (j in 0 until areas.length()) {
                val a = areas.getString(j)
                if (Prefs.wasIn(visits, a, ts)) here.put(a)
            }
            if (here.length() > 0) mine.put(JSONObject(e.toString()).put("areas", here))
        }
        val data = JSONObject()
            .put("all", all)
            .put("mine", mine)
            .put("dark", dark)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Weather.lastLocation(this)?.let {
                data.put("me", JSONArray().put(it.latitude).put(it.longitude))
            }
        }
        return data.toString()
    }
}
