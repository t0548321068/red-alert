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
 * מפה מוכנה מראש: המפה (map.html עם כל הספריות והאזורים) נטענת ברקע כשהמסך הראשי פתוח,
 * וכשפותחים את המפה היא רק מוצגת - בלי השתהות של טעינה.
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

    /** טוען מפה ברקע אם אין אחת מוכנה (בהשהיה קצרה - שלא יאט את פתיחת המסך הראשי) */
    fun warm(c: Context, delayMs: Long = 1500) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ build(c.applicationContext) }, delayMs)
    }

    private fun build(app: Context) {
        val want = Prefs.darkTheme(app)
        if (web != null && want == dark) return
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
                if (view === web) loaded = true
                if (view === takenView) { takenView = null; onLoaded?.invoke(); onLoaded = null }
            }
        }
        // גודל המסך כבר עכשיו - שהמפה תיטען בגודל הנכון גם כשהיא עוד לא מוצגת
        val dm = app.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
        web = v
        v.loadUrl("file:///android_asset/map.html" + if (dark) "" else "#light")
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
        ctx?.baseContext = a   // מעכשיו שייכת למסך המפה (חלונות, הרשאות)
        if (loaded) whenLoaded() else { takenView = v; onLoaded = whenLoaded }
        return v
    }

    /** האפליקציה ברקע - משחררים את המפה המוכנה (זיכרון וסוללה); תיטען שוב כשחוזרים */
    fun release() { h.removeCallbacksAndMessages(null); drop() }

    private fun drop() {
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        web = null; loaded = false   // מפה שכבר נלקחה למסך המפה (takenView) ממשיכה כרגיל
    }
}
