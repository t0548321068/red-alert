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

    /** ממיר את היסטוריית ההתראות לנתונים למפה + רק האזורים שצריך */
    private fun buildData(dark: Boolean): String {
        val events = JSONArray()

        Prefs.history(this).filter { it.ts > 0 }.forEach { e ->
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

        val data = JSONObject().put("events", events).put("dark", dark)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Weather.lastLocation(this)?.let {
                data.put("me", JSONArray().put(it.latitude).put(it.longitude))
            }
        }
        return data.toString()
    }
}
