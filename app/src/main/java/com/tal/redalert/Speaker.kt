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
        stopClips()
        finish()
    }

    // ---- הקלטות מוכנות (קול "אבר") - בלי אינטרנט, אותו קול בכל טלפון ----
    private var player: android.media.MediaPlayer? = null

    /** מקריא התראה מהקלטות; אם אין הקלטות מתאימות - בקול של הטלפון */
    fun speakAlert(title: String, areas: List<String>, shelter: Int?, done: () -> Unit) {
        val clips = try { clipsFor(title, areas, shelter) } catch (_: Exception) { emptyList() }
        if (clips.isEmpty()) { speak(textFor(title, areas, shelter), done); return }
        stopClips()
        onDone = done
        playClips(clips, 0)
    }

    private fun playClips(clips: List<String>, i: Int) {
        if (i >= clips.size) { stopClips(); finish(); return }
        try {
            val fd = c.assets.openFd(clips[i])
            val mp = android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setOnCompletionListener { it.release(); if (player === it) player = null; playClips(clips, i + 1) }
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; playClips(clips, i + 1); true }
                prepare()
            }
            player = mp
            mp.start()
        } catch (_: Exception) { playClips(clips, i + 1) }
    }

    private fun stopClips() {
        player?.let { try { it.stop() } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
        player = null
    }

    private fun has(path: String) = try { c.assets.openFd(path).close(); true } catch (_: Exception) { false }

    /** שם אזור בעברית → קובץ (voice/areas/<שם באנגלית>.mp3) */
    private val index: Map<String, String> by lazy {
        try {
            val o = org.json.JSONObject(c.assets.open("voice/index.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { "voice/" + o.getString(it) }
        } catch (_: Exception) { emptyMap() }
    }

    /** סוג → אזורים (עד 4, ואם יש עוד "ואזורים נוספים") → זמן להגעה */
    private fun clipsFor(title: String, areas: List<String>, shelter: Int?): List<String> {
        val level = AlertService.levelOf(title)
        val t = when {
            level == AlertService.LEVEL_END -> "event_ended"
            level == AlertService.LEVEL_PRE -> "early_warning"
            title.contains("טילים") || title.contains("רקטות") -> "rockets"
            title.contains("כלי טיס") -> "hostile_aircraft"
            title.contains("מחבלים") -> "terrorist_infiltration"
            title.contains("רעידת") -> "earthquake"
            title.contains("צונאמי") -> "tsunami"
            title.contains("חומרים מסוכנים") -> "hazardous_materials"
            title.contains("רדיולוג") -> "radiological"
            title.contains("קונבנציונלי") -> "nonconventional"
            else -> "red_alert"
        }
        val first = "voice/phrases/$t.mp3"
        val out = mutableListOf(first)
        val files = areas.mapNotNull { raw -> index[raw] ?: AreaData.match(c, raw).firstNotNullOfOrNull { index[it] } }.distinct()
        val shown = if (files.size <= 5) files else files.take(4)
        out += shown
        if (files.size > shown.size) out += "voice/phrases/more_areas.mp3"
        if (level == AlertService.LEVEL_ALERT && shelter != null) {
            val s = listOf(0, 15, 30, 45, 60, 90).minByOrNull { Math.abs(it - shelter) }
            out += "voice/phrases/" + when (s) { 0 -> "time_immediate"; 15 -> "time_15s"; 30 -> "time_30s"
                45 -> "time_45s"; 60 -> "time_1min"; else -> "time_1_5min" } + ".mp3"
        }
        return out.filter { has(it) }.takeIf { it.isNotEmpty() && it.first() == first } ?: emptyList()
    }

    fun shutdown() { stopClips(); try { tts.shutdown() } catch (_: Exception) { } }

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
