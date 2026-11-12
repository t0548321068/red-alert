package com.tal.tzofaradmin

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * הקראה בקול של הטלפון - לעדכונים (כמו "רעידת אדמה - הנחיות חזרה למבנים"), שאין להם הקלטה.
 * כמו בצופר חירום: המנוע של Google אם מותקן, קול עברי, מועדף קול גברי (ואם אין - קול מונמך)
 */
class Tts(private val c: Context) {
    private var ready = false
    /** תקלה בקול של הטלפון (מוצגת למשתמש), או null */
    @Volatile var error: String? = null; private set
    private var triedDefault = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null
    private lateinit var tts: TextToSpeech

    init { start("com.google.android.tts") }

    private fun start(engine: String?) {
        val listener = TextToSpeech.OnInitListener { st ->
            if (st != TextToSpeech.SUCCESS) {
                // המנוע של Google לא עלה - מנסים את המנוע הרגיל של הטלפון
                if (!triedDefault) { triedDefault = true; try { tts.shutdown() } catch (_: Exception) { }; start(null) }
                else fail("מנוע ההקראה של הטלפון לא עלה")
                return@OnInitListener
            }
            run {
                val r = tts.setLanguage(java.util.Locale("he", "IL"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (!triedDefault) { triedDefault = true; try { tts.shutdown() } catch (_: Exception) { }; start(null); return@OnInitListener }
                    fail("אין קול עברי בטלפון (הגדרות ← המרת טקסט לדיבור)"); return@OnInitListener
                }
                tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts.setSpeechRate(0.95f)
                val hebrew = try { tts.voices?.filter { it.locale.language == "he" || it.locale.language == "iw" }.orEmpty()
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality })) } catch (_: Exception) { emptyList() }
                fun male(v: android.speech.tts.Voice) = v.name.contains("male", true) && !v.name.contains("female", true)
                val v = hebrew.firstOrNull { male(it) }
                if (v != null) try { tts.voice = v } catch (_: Exception) { }
                tts.setPitch(if (v == null) 0.72f else 1f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finish() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { toast("ההקראה בקול הטלפון נכשלה"); finish() }
                    override fun onError(id: String?, code: Int) { toast("ההקראה בקול הטלפון נכשלה (קוד $code)"); finish() }
                })
                ready = true
                pending?.let { (t, d) -> pending = null; speak(t, d) }
            }
        }
        tts = if (engine != null) TextToSpeech(c.applicationContext, listener, engine) else TextToSpeech(c.applicationContext, listener)
    }

    private fun fail(msg: String) {
        error = msg
        val p = pending; pending = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show()
            p?.second?.invoke()
        }
    }

    private fun toast(m: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_LONG).show() }
    private fun finish() { val d = onDone; onDone = null; d?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) } }

    fun speak(text: String, done: () -> Unit) {
        error?.let { fail(it); done(); return }
        if (!ready) { pending = text to done; return }
        onDone = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "u") != TextToSpeech.SUCCESS) { onDone = null; fail("ההקראה בקול הטלפון נכשלה"); done() }
    }
    fun stop() { pending = null; onDone = null; try { tts.stop() } catch (_: Exception) { } }
    fun shutdown() { stop(); try { tts.shutdown() } catch (_: Exception) { } }

    companion object {
        /** אותו נוסח כמו בצופר חירום כשאין הקלטה */
        fun textFor(title: String, areas: List<String>): String {
            val names = areas.map { it.replace(" - ", " ").replace("-", " ") }
            val list = if (names.size <= 5) names.joinToString(", ") else names.take(4).joinToString(", ") + ", ועוד ${names.size - 4} אזורים"
            return "$title. $list."
        }
    }
}
