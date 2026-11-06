package com.jeremy.ai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jeremy.ai.backend.AlarmReceiver
import java.util.Collections

@Serializable
data class ScheduledTask(
    val taskId: String,
    val chatId: Long,
    val prompt: String,
    val executeAtTimestamp: Long
)

object AgentCronWorker {
    private val scheduledTasks: MutableList<ScheduledTask> = Collections.synchronizedList(mutableListOf<ScheduledTask>())

    fun addTask(task: ScheduledTask) {
        scheduledTasks.add(task)
    }

    fun scheduleAlarm(context: Context, botToken: String, chatId: Long, prompt: String, triggerAtMillis: Long) {
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
}
