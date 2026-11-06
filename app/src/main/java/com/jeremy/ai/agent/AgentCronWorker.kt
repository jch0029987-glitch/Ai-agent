package com.jeremy.ai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Collections

@Serializable
data class ScheduledTask(
    val taskId: String,
    val chatId: Long,
    val prompt: String,
    val executeAtTimestamp: Long
)

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val prompt = intent.getStringExtra("prompt") ?: return
        val botToken = intent.getStringExtra("bot_token") ?: BuildConfig.TELEGRAM_BOT_TOKEN

        GlobalScope.launch(Dispatchers.IO) {
            try {
                AgentBridge.processPromptWithTelegramUpdates(
                    prompt = prompt,
                    botToken = botToken,
                    chatId = chatId,
                    messageId = 0L
                )
            } catch (e: Exception) {
                // Handle failure
            }
        }
    }
}

object CronJobManager {
    private val scheduledTasks: MutableList<ScheduledTask> = Collections.synchronizedList(mutableListOf<ScheduledTask>())

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
                        } catch (e: Exception) {}
                        iterator.remove()
                    }
                }
                delay(10000L)
            }
        }
    }
}
