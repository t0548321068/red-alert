package com.tal.redalert.editor

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import kotlin.math.abs

/**
 * תצוגה בלבד: אין כאן שירות התרעות, אז לחיצה על שורה בעמוד "בדיקת התרעה" מפעילה את ההתרעה דרך TestFire.
 * לא משנה שום דבר אחר - כל הלחיצות ממשיכות לאפליקציה כרגיל
 */
object TestTaps {
    fun attach(a: Activity) {
        val w = a.window
        val cb = w.callback
        if (cb is Wrap) return
        w.callback = Wrap(a, w, cb)
    }

    private class Wrap(val a: Activity, val w: Window, val base: Window.Callback) : Window.Callback by base {
        private var dx = 0f; private var dy = 0f; private var moved = false
        private val slop = ViewConfiguration.get(a).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> if (abs(ev.rawX - dx) > slop || abs(ev.rawY - dy) > slop) moved = true
                MotionEvent.ACTION_UP -> if (!moved) tap(ev.rawX.toInt(), ev.rawY.toInt())
            }
            return base.dispatchTouchEvent(ev)
        }

        private fun tap(x: Int, y: Int) {
            val root = w.decorView
            var v = hit(root, x, y)
            while (v != null && v !== root && !v.hasOnClickListeners()) v = v.parent as? View
            if (v == null || v === root) return
            val label = firstText(v)
            if (label.isNotEmpty()) root.postDelayed({ TestFire.tap(a, label) }, 150)
        }
    }

    private fun hit(root: View, sx: Int, sy: Int): View? {
        fun inside(v: View): Boolean {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            return sx >= loc[0] && sy >= loc[1] && sx < loc[0] + v.width && sy < loc[1] + v.height
        }
        fun go(v: View): View? {
            if (v.visibility != View.VISIBLE || v.alpha == 0f || !inside(v)) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) go(v.getChildAt(i))?.let { return it }
            return v
        }
        return go(root)
    }

    private fun firstText(v: View): String {
        if (v.visibility != View.VISIBLE) return ""
        if (v is TextView && !v.text.isNullOrBlank()) return v.text.toString().replace('\n', ' ').take(40)
        if (v is ViewGroup) for (i in 0 until v.childCount) firstText(v.getChildAt(i)).let { if (it.isNotEmpty()) return it }
        return ""
    }
}
