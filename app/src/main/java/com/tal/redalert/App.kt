package com.tal.redalert

import android.app.Application

/** מופעל לפני כל חלק אחר באפליקציה - כך קבצי הנתונים מהשרת זמינים בכל מקום */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DataFiles.init(this)
    }
}
