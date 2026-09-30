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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = Prefs.darkTheme(this)
        window.decorView.setBackgroundColor(Color.parseColor(if (dark) "#0E0E10" else "#F2F2F7"))

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(web)
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
                            view.evaluateJavascript("focusOn($names, ${intent.getLongExtra("focusTs", 0L)});", null)
                        }
                        locate(view)
                    }
                }.start()
            }
        }
        web.loadUrl("file:///android_asset/map.html")
    }

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
                    .put("level", e.level).put("areas", names))
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
