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
 * מפת מסך ההתרעה מוכנה מראש, כדי שתוצג מיד:
 * - בכל התרעה בארץ (גם מקדימה / במקום אחר) - נטענת ברקע ונשארת מוכנה 10 דקות אחרי ההתרעה האחרונה.
 *   גם כשההתרעה היא אצלך - הטעינה מתחילה ברגע שהשרת מעיר את הטלפון, במקביל לפתיחת המסך
 * - בהגדרה "מפת התרעה מוכנה תמיד" - נשארת מוכנה כל הזמן
 * מפה שהוצגה לא חוזרת לשימוש - אחרי מסך ההתרעה נטענת מפה חדשה (אם עדיין בזמן אירוע / "תמיד")
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled")
object MiniPreload {
    const val EVENT_HOLD_MS = 10 * 60_000L
    private var web: WebView? = null
    private var ctx: MutableContextWrapper? = null
    private var dark = true
    private var loaded = false
    private var takenView: WebView? = null
    private var onLoaded: (() -> Unit)? = null
    /** עד מתי להחזיק מפה מוכנה (זמן אירוע) */
    private var until = 0L
    private val h = Handler(Looper.getMainLooper())
    private val expire = Runnable { if (!always()) drop() }

    private fun always() = DataFiles.app?.let { Prefs.alertMapAlways(it) } ?: false

    /** התרעה בארץ (מכל מקור): מפה מוכנה ל-10 הדקות הבאות. נקרא מכל thread */
    fun onEvent(c: Context) {
        val app = c.applicationContext
        h.post {
            if (!Prefs.alertMap(app)) return@post
            until = System.currentTimeMillis() + EVENT_HOLD_MS
            build(app)
            h.removeCallbacks(expire); h.postDelayed(expire, EVENT_HOLD_MS)
        }
    }

    /** "מוכנה תמיד" הודלקה / השירות עלה - טוענים אם צריך; כובתה - משחררים אם לא בזמן אירוע */
    fun sync(c: Context) {
        val app = c.applicationContext
        h.post {
            if (Prefs.alertMap(app) && (always() || System.currentTimeMillis() < until)) build(app)
            else drop()
        }
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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest) =
                DataFiles.web(app, req.url.toString())
            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) loaded = true
                if (view === takenView) { takenView = null; onLoaded?.invoke(); onLoaded = null }
            }
        }
        // בערך בגודל של המפה במסך ההתרעה (התאמה מדויקת כשהיא מוצגת)
        val dm = app.resources.displayMetrics
        val hh = (dm.heightPixels * 0.34f).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, dm.widthPixels, hh)
        web = v
        v.loadUrl("file:///android_asset/map.html#minipre" + if (dark) "" else "-light")
    }

    /** המפה המוכנה למסך ההתרעה (או null). whenLoaded - מיד אם נטענה, או כשתסתיים הטעינה */
    fun take(a: android.app.Activity, whenLoaded: () -> Unit): WebView? {
        val v = web ?: return null
        if (dark != Prefs.darkTheme(a)) { drop(); return null }
        web = null
        ctx?.baseContext = a
        if (loaded) whenLoaded() else { takenView = v; onLoaded = whenLoaded }
        return v
    }

    /** מסך ההתרעה נסגר - מפה חדשה אם עדיין צריך */
    fun afterUse(c: Context) { takenView = null; onLoaded = null; sync(c) }

    private fun drop() {
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        web = null; loaded = false
    }
}
