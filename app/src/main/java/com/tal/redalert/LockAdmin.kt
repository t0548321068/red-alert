package com.tal.redalert

import android.app.admin.DeviceAdminReceiver

/** הרשאת "נעילת מסך" בלבד - כדי שהפעלת מצב שבת מכפתור השעון תנעל את המסך מיד */
class LockAdmin : DeviceAdminReceiver()
