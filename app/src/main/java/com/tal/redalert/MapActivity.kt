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

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Thread {
                    val (data, areas) = buildData(dark)
                    runOnUiThread {
                        view.evaluateJavascript("render($data, $areas);", null)
                    }
                }.start()
            }
        }
        web.loadUrl("file:///android_asset/map.html")
    }

    /** ממיר את היסטוריית ההתראות לנתונים למפה + רק האזורים שצריך */
    private fun buildData(dark: Boolean): Pair<String, String> {
        val all = AreaData.areas(this)
        val needed = JSONObject()
        val events = JSONArray()

        Prefs.history(this).filter { it.ts > 0 }.forEach { e ->
            val names = JSONArray()
            e.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
                AreaData.match(this, raw).forEach { name ->
                    names.put(name)
                    if (!needed.has(name)) needed.put(name, all.getJSONObject(name))
                }
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
        return data.toString() to needed.toString()
    }
}
