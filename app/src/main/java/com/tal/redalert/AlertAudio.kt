package com.tal.redalert

import com.tal.redalert.AlertService.Companion.HEADSETS
import com.tal.redalert.AlertService.Companion.LEVEL_ALERT
import com.tal.redalert.AlertService.Companion.builtinSpeaker
import com.tal.redalert.AlertService.Companion.start
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// שירות ההתרעות: צליל, עוצמה, מצב שקט / נא לא להפריע, אוזניות ושיחה

/** מה שצריך להחזיר (נא לא להפריע / מצב צלצול / עוצמת שעון) - נשמר גם בקובץ, למקרה שהשירות נסגר באמצע */
internal fun AlertService.restoreSp() = getSharedPreferences("restore", android.content.Context.MODE_PRIVATE)

/** השירות עלה מחדש אחרי שנסגר באמצע התרעה - מחזירים את ההגדרות למה שהיו */
internal fun AlertService.restorePending() {
    val sp = restoreSp()
    // רק אם השירות נסגר באמצע התרעה ממש עכשיו - אחרי זמן רב המשתמש כבר שינה בעצמו
    if (System.currentTimeMillis() - sp.getLong("at", 0) > 10 * 60_000L) { sp.edit().clear().apply(); return }
    if (sp.contains("dnd")) { dndSaved = sp.getInt("dnd", -1); dndRestore.run() }
    if (sp.contains("ringer")) { ringerSaved = sp.getInt("ringer", -1); restoreRinger.run() }
    if (sp.contains("alarmVol")) { savedAlarmVol = sp.getInt("alarmVol", -1); restoreAlarmVol.run() }
}

internal fun AlertService.overrideDnd(force: Boolean = false): Unit = synchronized(audioLock) {
    if (!force && !Prefs.dndOverride(this)) return
    val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!nm.isNotificationPolicyAccessGranted) return
    val cur = nm.currentInterruptionFilter
    if (cur == NotificationManager.INTERRUPTION_FILTER_ALL) return
    if (dndSaved == -1) { dndSaved = cur; restoreSp().edit().putLong("at", System.currentTimeMillis()).putInt("dnd", cur).apply() }
    try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) { return }
    main.removeCallbacks(dndRestore)
    main.postDelayed(dndRestore, 60_000)
}

internal fun AlertService.boostAlarmVolume(ms: Long): Unit = synchronized(audioLock) {
    try {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val s = android.media.AudioManager.STREAM_ALARM
        if (savedAlarmVol < 0) { savedAlarmVol = am.getStreamVolume(s); restoreSp().edit().putLong("at", System.currentTimeMillis()).putInt("alarmVol", savedAlarmVol).apply() }
        am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
        main.removeCallbacks(restoreAlarmVol)
        main.postDelayed(restoreAlarmVol, ms + 500)
    } catch (_: Exception) { }
}

internal fun AlertService.ringerToNormal(ms: Long): Boolean = synchronized(audioLock) {
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    return try {
        if (ringerSaved == -1) { ringerSaved = am.ringerMode; restoreSp().edit().putLong("at", System.currentTimeMillis()).putInt("ringer", ringerSaved).apply() }
        am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL ||
            am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0) { restoreRinger.run(); return false }
        main.removeCallbacks(restoreRinger)
        main.postDelayed(restoreRinger, ms)
        true
    } catch (_: Exception) { restoreRinger.run(); false }
}

internal fun AlertService.playAlarm(level: Int, type: Int, durationMs: Long, usage: Int = AudioAttributes.USAGE_ALARM) {
    val uri = Sounds.uri(this, Sounds.effective(this, level), level) ?: return
    main.post {
        ringtone?.stop()
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
        val r = ringtone
        main.postDelayed({ if (ringtone === r) r?.stop() }, durationMs)
    }
}

internal fun AlertService.holdFocus(ms: Long): Unit = synchronized(audioLock) {
    main.removeCallbacks(releaseFocus); releaseFocus.run()
    try {
        val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener { }
            .build()
        (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).requestAudioFocus(r)
        focusReq = r
        main.postDelayed(releaseFocus, ms)
    } catch (_: Exception) { }
}

/** אוזניות מחוברות (חוטיות, USB או בלוטות') */
internal fun AlertService.headsetOn(am: android.media.AudioManager): Boolean = try {
    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
} catch (_: Exception) { false }

internal fun AlertService.playOnSpeaker(level: Int, durationMs: Long, usage: Int) {
    val uri = Sounds.uri(this, Sounds.effective(this, level), level) ?: return
    main.post {
        ringtone?.stop(); stopSpeaker()
        try {
            spk = android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@playOnSpeaker, uri)
                isLooping = level == LEVEL_ALERT
                builtinSpeaker(this@playOnSpeaker)?.let { setPreferredDevice(it) }
                prepare(); start()
            }
        } catch (_: Exception) { stopSpeaker(); playAlarm(level, RingtoneManager.TYPE_ALARM, durationMs, usage) }
        val p = spk
        main.postDelayed({ if (spk === p) stopSpeaker() }, durationMs)
    }
}

internal fun AlertService.stopSpeaker() {
    spk?.let { try { it.stop() } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
    spk = null
}

/** בשיחה: צפצופים באוזן של השיחה (הצד השני לא שומע). ירי - 3 צפצופים, מקדימה וסיום - אחד */
internal fun AlertService.callBeeps(level: Int) {
    val n = if (level == LEVEL_ALERT) 3 else 1
    try {
        val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 100)
        for (i in 0 until n) main.postDelayed({ try { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 500) } catch (_: Exception) { } }, i * 800L)
        main.postDelayed({ try { tg.release() } catch (_: Exception) { } }, n * 800L + 600)
    } catch (_: Exception) { }
}
