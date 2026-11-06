package com.jeremy.ai.backend

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


object CronJobManager {
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())

    fun scheduleExactAlarm(context: Context, botToken: String, chatId: Long, prompt: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("prompt", prompt)
            putExtra("bot_token", botToken)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            chatId.toInt() + triggerAtMillis.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            // Requires SCHEDULE_EXACT_ALARM permission on Android 12+
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // Fallback to inexact alarm if exact permission is denied
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
