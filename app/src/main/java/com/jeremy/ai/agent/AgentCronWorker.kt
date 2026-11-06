package com.jeremy.ai.backend

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jeremy.ai.agent.AgentBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections

object CronJobManager {
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())

    fun addTask(task: ScheduledTask) {
        scheduledTasks.add(task)
    }

    fun scheduleExactAlarm(context: Context, botToken: String, chatId: Long, prompt: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("prompt", prompt)
            putExtra("bot_token", botToken)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (chatId + triggerAtMillis).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun startDispatcher(botToken: String) {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val now = System.currentTimeMillis()
                val iterator = scheduledTasks.iterator()
                while (iterator.hasNext()) {
                    val task = iterator.next()
                    if (now >= task.executeAtTimestamp) {
                        try {
                            AgentBridge.processPromptWithTelegramUpdates(
                                prompt = task.prompt,
                                botToken = botToken,
                                chatId = task.chatId,
                                messageId = 0L
                            )
                        } catch (e: Exception) {
                            // Log or handle dispatch error
                        }
                        iterator.remove()
                    }
                }
                delay(10000L)
            }
        }
    }
}
