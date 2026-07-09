package com.home.launcher.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class NotificationEntry(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val icon: android.graphics.drawable.Drawable?,
    val contentIntent: android.app.PendingIntent?
)

class NotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "NotifListener"

        private val notifications = ConcurrentHashMap<String, NotificationEntry>()
        private val changeListeners = CopyOnWriteArraySet<() -> Unit>()
        private var connected = false

        fun isConnected(): Boolean = connected
        fun getNotifications(): List<NotificationEntry> = notifications.values.toList()
        fun getNotificationsForPackage(pkg: String): List<NotificationEntry> =
            notifications.values.filter { it.packageName == pkg }

        fun getActivePackages(): Map<String, Int> {
            val map = mutableMapOf<String, Int>()
            for (entry in notifications.values) {
                map[entry.packageName] = (map[entry.packageName] ?: 0) + 1
            }
            return map
        }

        fun dismiss(key: String, nm: android.app.NotificationManager?) {
            val entry = notifications[key] ?: return
            nm?.cancel(entry.packageName, key.hashCode())
            notifications.remove(key)
            notifyChanged()
        }

        fun addChangeListener(listener: () -> Unit) {
            changeListeners.add(listener)
        }

        fun removeChangeListener(listener: () -> Unit) {
            changeListeners.remove(listener)
        }

        private fun notifyChanged() {
            for (listener in changeListeners) {
                listener()
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "Notification listener connected")
        for (sbn in activeNotifications) {
            addNotification(sbn)
        }
        notifyChanged()
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        addNotification(sbn)
        notifyChanged()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notifications.remove(sbn.key)
        notifyChanged()
    }

    fun removeNotification(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {}
        notifications.remove(key)
        notifyChanged()
    }

    private fun addNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification
        val extras = notif.extras
        val title = extras?.getString(Notification.EXTRA_TITLE)
        val text = extras?.getString(Notification.EXTRA_TEXT) ?: extras?.getString(Notification.EXTRA_BIG_TEXT)
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val entry = NotificationEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            icon = notif.smallIcon?.let { icon ->
                try { packageManager.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }
            },
            contentIntent = notif.contentIntent
        )
        notifications[sbn.key] = entry
    }

}
