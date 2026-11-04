package com.tal.tzofarrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** אחרי הפעלה מחדש של הטלפון או עדכון - הממסר עולה לבד */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { RelayService.start(c) }
}
