package com.jeremy.ai.agent.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedQueue

data class AgentNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

class AgentNotificationListener : NotificationListenerService() {

    companion object {
        // Keep a rolling buffer of the last 20 notifications in memory
        private const val MAX_HISTORY = 20
        val recentNotifications = ConcurrentLinkedQueue<AgentNotification>()

        fun getContextSummary(): String {
            if (recentNotifications.isEmpty()) return "No recent notifications."
            return recentNotifications.joinToString("\n") { 
                "- [${it.packageName}] ${it.title}: ${it.text}" 
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { notification ->
            val extras = notification.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            
            // Filter out empty notifications or system tickers if desired
            if (title.isNotEmpty() || text.isNotEmpty()) {
                val item = AgentNotification(
                    packageName = notification.packageName,
                    title = title,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
                
                recentNotifications.add(item)
                while (recentNotifications.size > MAX_HISTORY) {
                    recentNotifications.poll()
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Optional: Handle notification dismissal if needed
    }
}
