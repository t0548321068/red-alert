package com.tal.tzofareditor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * עריכת צופר: המסכים של צופר חירום (העתק - לא עובד באמת) בתוך דף אחד (assets/web).
 * לוחצים על רכיב, משנים צבע / גודל / טקסט / מיקום או כותבים הערה, ושולחים את כל השינויים בבת אחת לשרת.
 * כאן רק המעטפת: הדף, הכניסה לשרת, השליחה והעדכון האוטומטי
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        @Suppress("DEPRECATION") window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION") window.navigationBarColor = Color.BLACK
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // השינויים נשמרים בטלפון עד השליחה
            settings.allowFileAccess = true
            settings.textZoom = 100             // גודל הטקסט כמו באפליקציה (בלי הגדלת הטלפון)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Native")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(web)
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - Update.lastCheck > 60_000 || (Update.needPermission && packageManager.canRequestPackageInstalls()))
            Update.check(this) { ui.post { js("window.onUpdate && onUpdate()") } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // הדף מחליט: סוגר חלון / חוזר למסך הקודם. אם אין לאן - יוצאים
        web.evaluateJavascript("window.onBack ? onBack() : false") { r -> if (r != "true") finish() }
    }

    private fun js(s: String) = ui.post { web.evaluateJavascript(s, null) }
    private fun reply(id: Int, o: Any?) = js("window.__cb($id, ${JSONObject.quote(o?.toString() ?: "")})")

    private fun sp() = getSharedPreferences("editor", Context.MODE_PRIVATE)

    inner class Bridge {
        @JavascriptInterface fun asset(path: String): String = try {
            if (path.contains("..")) "" else assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { "" }
        @JavascriptInterface fun version(): String = Update.current(this@MainActivity)
        @JavascriptInterface fun updateStatus(): String = Update.status
        @JavascriptInterface fun needInstallPermission(): Boolean = Update.needPermission
        @JavascriptInterface fun openInstallPermission() {
            ui.post { try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) { } }
        }
        @JavascriptInterface fun loggedIn(): Boolean = !sp().getString("token", "").isNullOrEmpty()

        /** כניסה עם קוד האתר (כמו בניהול צופר). תשובה: "" = הצליח, אחרת הודעת שגיאה */
        @JavascriptInterface fun login(code: String, id: Int) = Thread { reply(id, doLogin(code) ?: "") }.start()

        /** מצב השליחות מהשרת. תשובה: JSON, או "" אם אין חיבור */
        @JavascriptInterface fun state(id: Int) = Thread { reply(id, call(null) ?: "") }.start()

        /** שליחת כל השינויים. תשובה: JSON של המצב החדש, או "" אם נכשל */
        @JavascriptInterface fun send(items: String, id: Int) = Thread {
            val body = try { JSONObject().put("op", "send").put("items", org.json.JSONArray(items)) } catch (_: Exception) { null }
            reply(id, body?.let { call(it) } ?: "")
        }.start()
    }

    private fun doLogin(code: String): String? {
        val conn = URL("$SITE/api/login").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
            when (conn.responseCode) {
                200 -> {
                    val tok = conn.headerFields["Set-Cookie"].orEmpty().firstNotNullOfOrNull { Regex("tz=([^;]+)").find(it)?.groupValues?.get(1) }
                    if (tok.isNullOrEmpty()) "הכניסה נכשלה" else { sp().edit().putString("token", tok).apply(); null }
                }
                401 -> "קוד שגוי"
                429 -> "יותר מדי ניסיונות - נסה שוב בעוד דקה"
                else -> "השרת לא ענה"
            }
        } catch (_: Exception) { "אין חיבור לשרת" } finally { conn.disconnect() }
    }

    private fun call(body: JSONObject?): String? {
        val tok = sp().getString("token", "") ?: return null
        val conn = URL("$SITE/api/design").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.useCaches = false
            conn.setRequestProperty("Cookie", "tz=$tok")
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            when (conn.responseCode) {
                200 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                401 -> { sp().edit().remove("token").apply(); "{\"login\":true}" }   // פג תוקף - נכנסים שוב
                else -> null
            }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    companion object { private const val SITE = "https://tzofar-app.duckdns.org" }
}
