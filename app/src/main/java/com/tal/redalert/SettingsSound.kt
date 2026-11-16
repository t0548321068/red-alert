package com.tal.redalert


// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// עמודי ההגדרות: צלילים ורטט, הקראה ושעות שקט

internal fun MainActivity.showSoundSettings() = ouiPage("צלילים ורטט") { body, rebuild ->
    // השתקה זמנית - כרטיס נפרד למעלה
    val m = ouiCard()
    val muted = Prefs.mutedNow(this)
    ouiRow(m, "השתקה זמנית", sub = muteLabel(), on = muted,
        onToggle = { if (muted) Prefs.setMuteUntil(this, 0L) else Prefs.setMuteUntil(this, System.currentTimeMillis() + 60 * 60_000); rebuild() }) {
        chooseMute { rebuild() } }
    ouiAdd(body, m)
    ouiNote(body, "כל ההתרעות מתקבלות בלי צליל ורטט – לזמן שתבחר")
    val c = ouiCard()
    val snd = Prefs.soundsOn(this); val vib = Prefs.vibesOn(this)
    ouiRow(c, "צלילים", sub = if (!snd) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
        on = snd, onToggle = { Prefs.setSoundsOn(this, !snd); rebuild() }) {
        openNotificationSettings() }
    ouiDivider(c)
    ouiRow(c, "רטט", sub = if (!vib) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
        on = vib, onToggle = { Prefs.setVibesOn(this, !vib); rebuild() }) {
        openNotificationSettings() }
    ouiDivider(c)
    ouiRow(c, "הקראה", sub = onOff(Prefs.speakAlerts(this)), on = Prefs.speakAlerts(this),
        onToggle = { Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }) { showSpeech() }
    ouiDivider(c)
    val loud = Prefs.loudSound(this)
    ouiRow(c, "צליל חזק", sub = if (loud) "תמיד בעוצמה מלאה – גם כשהעוצמה נמוכה, בשעות שקט ובמצב שקט" else "כבוי", on = loud) {
        Prefs.setLoudSound(this, !loud); rebuild() }
    ouiDivider(c)
    val hs = Prefs.speakerWithHeadset(this)
    ouiRow(c, "גם מהרמקול עם אוזניות", sub = if (hs) "כשמחוברות אוזניות – הצופר וההקראה יוצאים גם מהטלפון" else "כבוי", on = hs) {
        Prefs.setSpeakerWithHeadset(this, !hs); rebuild() }
    ouiAdd(body, c)

    val q = ouiCard()
    ouiRow(q, "שעות שקט",
        sub = if (Prefs.quietOn(this)) "${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}" else "כבוי",
        on = Prefs.quietOn(this),
        onToggle = { Prefs.setQuietOn(this, !Prefs.quietOn(this)); rebuild() }) { showQuietHours() }
    ouiDivider(q)
    // רק הדלקה/כיבוי - ההרשאה עצמה במסך ההרשאות
    ouiRow(q, "עקיפת נא לא להפריע", sub = onOff(Prefs.dndOverride(this)), on = Prefs.dndOverride(this)) {
        Prefs.setDndOverride(this, !Prefs.dndOverride(this)); rebuild() }
    ouiDivider(q)
    ouiRow(q, "עקיפת מצב שקט", sub = if (Prefs.bypassSilent(this)) "צליל גם כשהטלפון על שקט או רטט" else "כבוי",
        on = Prefs.bypassSilent(this)) { Prefs.setBypassSilent(this, !Prefs.bypassSilent(this)); rebuild() }
    ouiDivider(q)
    // מצב שבת: המתג מדליק "אוטומטי" או מכבה. דלוק תמיד - בעמוד מצב שבת
    val sh = Prefs.shabbatMode(this)
    ouiRow(q, "מצב שבת", sub = Shabbat.label(sh), on = sh != Shabbat.OFF,
        onToggle = { Prefs.setShabbatMode(this, if (sh == Shabbat.OFF) Shabbat.AUTO else Shabbat.OFF); rebuild() }) { chooseShabbat() }
    ouiAdd(body, q)
    ouiNote(body, "מצב שבת עוקף את שעות השקט, נא לא להפריע ומצב שקט")
}

/** עמוד הקראה: הדלקה/כיבוי והשמעת דוגמה */
internal fun MainActivity.showSpeech() = ouiPage("הקראה") { body, rebuild ->
    val c = ouiCard()
    ouiRow(c, "הקראה בקול", on = Prefs.speakAlerts(this)) {
        Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }
    ouiDivider(c)
    ouiRow(c, "השמע דוגמה", sub = "ירי רקטות וטילים באזורים שלך") {
        // דוגמה בקול של ההתראות (הקלטות "אבר")
        val sp = testSpeaker ?: Speaker(this).also { testSpeaker = it }
        val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
        val areas = mine.ifEmpty { listOf("תל אביב - מרכז העיר") }.take(3)
        sp.speakAlert("ירי רקטות וטילים", areas, AreaData.shelterSeconds(this, areas) ?: 90) {}
    }
    ouiAdd(body, c)
    ouiNote(body, "מקריא סוג, אזורים וזמן להגעה – ואז הצליל. במצב שבת ההקראה פועלת תמיד")
}

/** עמוד שעות שקט: הדלקה/כיבוי, שעת התחלה וסיום */
internal fun MainActivity.showQuietHours() = ouiPage("שעות שקט") { body, rebuild ->
    val on = Prefs.quietOn(this)
    val sw = ouiCard()
    ouiRow(sw, "שעות שקט", on = on) { Prefs.setQuietOn(this, !on); rebuild() }
    ouiAdd(body, sw)
    ouiNote(body, "בשעות האלה התרעות מקדימות והודעות סיום אירוע מגיעות בשקט. התרעות ירי נשמעות תמיד")
    val t = ouiCard()
    ouiRow(t, "התחלה", end = hhmm(Prefs.quietFrom(this)), endBig = true) {
        pickTime("שעת התחלה", Prefs.quietFrom(this)) { Prefs.setQuiet(this, it, Prefs.quietTo(this)); rebuild() } }
    ouiDivider(t)
    ouiRow(t, "סיום", end = hhmm(Prefs.quietTo(this)), endBig = true) {
        pickTime("שעת סיום", Prefs.quietTo(this)) { Prefs.setQuiet(this, Prefs.quietFrom(this), it); rebuild() } }
    ouiAdd(body, t)
    ouiNote(body, "מצב שבת עוקף את שעות השקט")
}
