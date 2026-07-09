package com.home.launcher.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context

object NotificationAccess {
    fun isListenerAccessGranted(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val component = ComponentName(context, NotificationListener::class.java)
        return notificationManager.isNotificationListenerAccessGranted(component)
    }
}
