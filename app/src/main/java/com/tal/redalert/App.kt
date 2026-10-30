package com.tal.redalert

import android.app.Application

/** מופעל לפני כל חלק אחר באפליקציה - כך קבצי הנתונים מהשרת זמינים בכל מקום, וקריסות נתפסות */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Reporter.install(this)   // קריסה - נשמרת ונשלחת לשרת
        DataFiles.init(this)
    }
}
