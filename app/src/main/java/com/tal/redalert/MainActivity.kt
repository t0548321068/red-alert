package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var last: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        col.addView(TextView(this).apply {
            text = "צבע אדום"
            textSize = 30f
            setTextColor(Color.parseColor("#FF3B30"))
        })

        status = TextView(this).apply { textSize = 18f; setPadding(0, 24, 0, 24) }
        col.addView(status)

        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                val on = !Prefs.enabled(this@MainActivity)
                Prefs.setEnabled(this@MainActivity, on)
                if (on) AlertService.start(this@MainActivity) else AlertService.stop(this@MainActivity)
                refresh()
            }
        }
        col.addView(toggle)

        col.addView(TextView(this).apply {
            text = "ערים / אזורים (מופרדים בפסיק). ריק = כל הארץ"
            setPadding(0, 40, 0, 8)
        })
        val cities = EditText(this).apply {
            setText(Prefs.citiesRaw(this@MainActivity))
            hint = "לדוגמה: תל אביב, רמת גן"
        }
        col.addView(cities)
        col.addView(Button(this).apply {
            text = "שמור ערים"
            setOnClickListener { Prefs.setCities(this@MainActivity, cities.text.toString()) }
        })

        col.addView(Button(this).apply {
            text = "שלח התראת בדיקה"
            setOnClickListener {
                Prefs.setEnabled(this@MainActivity, true)
                AlertService.start(this@MainActivity, test = true)
                refresh()
            }
        })

        col.addView(TextView(this).apply {
            text = "התראה אחרונה:"
            setPadding(0, 40, 0, 8)
        })
        last = TextView(this).apply { textSize = 16f }
        col.addView(last)

        col.addView(Button(this).apply {
            text = "פתח הגדרות מסך מלא"
            setOnClickListener { openFullScreenSettings() }
        })

        setContentView(ScrollView(this).apply { addView(col) })
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        status.text = if (on) "● פעיל – מאזין להתראות" else "○ כבוי"
        status.setTextColor(if (on) Color.parseColor("#34C759") else Color.GRAY)
        toggle.text = if (on) "כבה" else "הפעל"
        last.text = Prefs.lastAlert(this)
    }

    @SuppressLint("BatteryLife")
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        }
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
