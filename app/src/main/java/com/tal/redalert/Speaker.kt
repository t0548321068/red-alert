package com.tal.redalert

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * הקראה בקול של ההתראה (סוג, אזורים, זמן להגעה) בקול העברי של הטלפון.
 * מושמע בערוץ "שעון מעורר" - נשמע גם כשהטלפון על שקט.
 */
class Speaker(private val c: Context) {

    private var ready = false
    /** null = עוד לא ידוע, false = אין קול עברי במכשיר */
    var hebrewOk: Boolean? = null
        private set
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(c.applicationContext, { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("he", "IL"))
            hebrewOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            tts.setSpeechRate(0.95f)
            applyVoice()
            ready = true
        } else hebrewOk = false
    }, GOOGLE_TTS)   // מנוע ההקראה של Google (קול עברי טבעי). אם לא מותקן - המנוע הרגיל של הטלפון

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finish() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { finish() }
        })
    }

    /**
     * קול גבר / אישה. אם במכשיר יש קול עברי גברי - בוחרים בו.
     * אם אין (לרוב יש רק קול נשי) - מנמיכים את גובה הקול כדי שיישמע גברי.
     */
    fun applyVoice() {
        val male = Prefs.maleVoice(c)
        // קולות עבריים - הכי איכותיים קודם, ומועדפים קולות שלא צריכים אינטרנט
        val hebrew = try {
            tts.voices?.filter { it.locale.language == "he" || it.locale.language == "iw" }.orEmpty()
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }))
        } catch (_: Exception) { emptyList() }
        fun isMale(v: android.speech.tts.Voice) =
            v.name.contains("male", true) && !v.name.contains("female", true)
        val chosen = if (male) hebrew.firstOrNull { isMale(it) } else hebrew.firstOrNull { !isMale(it) }
        if (chosen != null) try { tts.voice = chosen } catch (_: Exception) { }
        // אין קול גברי אמיתי - מנמיכים את הקול
        tts.setPitch(if (male && (chosen == null || !isMale(chosen))) 0.72f else 1.0f)
    }

    private fun finish() {
        val d = onDone; onDone = null; d?.invoke()
    }

    /** מקריא; done נקרא בסוף (או מיד אם אין הקראה) */
    fun speak(text: String, done: () -> Unit) {
        if (!ready || hebrewOk != true) { done(); return }
        onDone = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "alert")
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) { }
        finish()
    }

    fun shutdown() = try { tts.shutdown() } catch (_: Exception) { }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        /** בניית הטקסט להקראה */
        fun textFor(title: String, areas: List<String>, shelter: Int?): String {
            val names = areas.map { it.replace(" - ", " ").replace("-", " ") }
            val list = if (names.size <= 5) names.joinToString(", ")
                       else names.take(4).joinToString(", ") + ", ועוד ${names.size - 4} אזורים"
            return when {
                title.contains("הסתיים") -> "האירוע הסתיים. $list."
                title.contains("בדקות הקרובות") -> "התראה מקדימה. בדקות הקרובות צפויות התרעות. $list."
                else -> buildString {
                    append("$title. $list.")
                    if (shelter != null) append(" זמן להגעה למרחב המוגן: ${AreaData.shelterText(shelter)}.")
                }
            }
        }
    }
}
