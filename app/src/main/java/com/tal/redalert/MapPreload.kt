package com.tal.redalert

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * מפה מוכנה מראש: המפה (map.html עם כל הספריות והאזורים) נטענת כשהמסך הראשי פתוח -
 * מאחורי המסך הראשי (מוסתרת מתחת לכיסוי בצבע הרקע), כך שהיא באמת מצוירת: ההתרעות, המיקום
 * והרחובות כבר טעונים באותו מקום שהמפה נפתחת בו. כשפותחים את המפה היא רק עוברת למסך המפה - בלי מסך אפור.
 * אחרי כל סגירה של המפה נטענת ברקע מפה חדשה ונקייה (בלי הזום / ההתמקדות של הפעם הקודמת).
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled")
object MapPreload {
    private var web: WebView? = null
    private var ctx: MutableContextWrapper? = null
    private var dark = true
    /** הדף נטען עד הסוף */
    var loaded = false; private set
    /** נקרא כשהדף נטען, אם המפה כבר נפתחה לפני כן */
    private var onLoaded: (() -> Unit)? = null
    private var takenView: WebView? = null
    /** "המיקום שלי" מהדף - עובר למסך המפה הפתוח */
    var locate: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    /** הכיסוי מעל המפה במסך הראשי (בצבע הרקע של המסך) */
    private var cover: View? = null
    private var host: android.app.Activity? = null

    /** צבע הכיסוי = צבע הרקע של המסך הראשי (גם כשהוא נצבע בזמן התרעה) */
    fun setCoverColor(c: Int) { cover?.setBackgroundColor(c) }

    /** טוען מפה ברקע אם אין אחת מוכנה (בהשהיה קצרה - שלא יאט את פתיחת המסך הראשי) */
    fun warm(c: Context, delayMs: Long = 1500) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ build(c.applicationContext, c as? android.app.Activity) }, delayMs)
    }

    private fun build(app: Context, act: android.app.Activity?) {
        val want = Prefs.darkTheme(app)
        if (web != null && want == dark) return
        // מצוירת רק מאחורי המסך הראשי (בלי מסך - אין טעם להחזיק אותה)
        val content = act?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        drop()
        dark = want
        val wrap = MutableContextWrapper(app)
        ctx = wrap
        loaded = false
        val v = WebView(wrap).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        v.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun locate() = h.post { locate?.invoke() }
        }, "App")
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest) =
                DataFiles.web(app, req.url.toString())   // יישובים מעודכנים מהשרת
            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) { loaded = true; prime(app, view) }
                if (view === takenView) { takenView = null; onLoaded?.invoke(); onLoaded = null }
            }
        }
        // מאחורי המסך הראשי: המפה, מעליה כיסוי בצבע הרקע (שגם בולע נגיעות), ומעליו המסך עצמו
        host = act
        wrap.baseContext = act
        content.addView(v, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        cover = View(act).apply {
            setBackgroundColor((act.window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color
                ?: if (dark) android.graphics.Color.BLACK else 0xFFF2F2F7.toInt())
            isClickable = true
        }
        content.addView(cover, 1, android.widget.FrameLayout.LayoutParams(-1, -1))
        web = v
        v.loadUrl("file:///android_asset/map.html" + if (dark) "" else "#light")
    }

    /** המפה נטענה: ההתרעות והמיקום האחרון - כך היא כבר במקום שבו תיפתח, והרחובות שם נטענים */
    private fun prime(app: Context, view: WebView) {
        Thread {
            val data = try { MapActivity.buildData(app, dark) } catch (_: Exception) { "" }
            h.post {
                if (view !== web || data.isEmpty()) return@post
                view.evaluateJavascript("render($data);", null)
                try { Weather.lastLocation(app) } catch (_: Exception) { null }?.let { l ->
                    view.evaluateJavascript("setMe(${l.latitude}, ${l.longitude}, ${l.accuracy});", null)
                }
            }
        }.start()
    }

    /**
     * המפה המוכנה למסך המפה (או null - אין מוכנה בערכת הנושא הנוכחית, והמסך יטען לבד).
     * whenLoaded נקרא מיד אם הדף כבר נטען, או כשיסתיים
     */
    fun take(a: android.app.Activity, whenLoaded: () -> Unit): WebView? {
        h.removeCallbacksAndMessages(null)
        val v = web ?: return null
        if (dark != Prefs.darkTheme(a)) { drop(); return null }
        web = null
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        removeCover()
        ctx?.baseContext = a   // מעכשיו שייכת למסך המפה (חלונות, הרשאות)
        if (loaded) whenLoaded() else { takenView = v; onLoaded = whenLoaded }
        return v
    }

    /** האפליקציה ברקע - משחררים את המפה המוכנה (זיכרון וסוללה); תיטען שוב כשחוזרים */
    fun release() { h.removeCallbacksAndMessages(null); drop() }

    private fun removeCover() {
        cover?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        cover = null; host = null
    }

    private fun drop() {
        removeCover()
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        web = null; loaded = false   // מפה שכבר נלקחה למסך המפה (takenView) ממשיכה כרגיל
    }
}
