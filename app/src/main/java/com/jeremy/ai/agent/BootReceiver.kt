package com.jeremy.ai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jeremy.ai.agent.BuildConfig

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Device boot or package update detected. Restoring agent schedules...")

            try {
                val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
                if (botToken.isBlank()) {
                    Log.w(TAG, "Telegram bot token missing during boot restoration.")
                    return
                }

                // 1. Restart background Ktor cron dispatcher loops
                try {
                    CronJobManager.startDispatcher(botToken)
                    Log.i(TAG, "CronJobManager dispatcher successfully restarted.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start CronJobManager dispatcher: ${e.localizedMessage}")
                }

                // 2. Restore pending exact alarms from persistent storage/database
                restorePendingAlarms(context, botToken)

            } catch (e: Exception) {
                Log.e(TAG, "Critical error during BootReceiver execution: ${e.localizedMessage}", e)
            }
        }
    }

    private fun restorePendingAlarms(context: Context, botToken: String) {
        // TODO: Query your local SQLite/Room database or encrypted SharedPreferences 
        // where pending scheduled tasks (chatId, prompt, targetTimestamp) are stored.
        // Below is the template pattern for re-registering them with AlarmManager:

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Example placeholder for retrieved pending tasks
        // val pendingTasks = LocalDatabase.getPendingTasks()
        val pendingTasks = emptyList<ScheduledTask>() 

        for (task in pendingTasks) {
            // Skip if the target time has already passed while the phone was off
            if (task.executeAtTimestamp <= System.currentTimeMillis()) continue

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("chat_id", task.chatId)
                putExtra("prompt", task.prompt)
                putExtra("bot_token", botToken)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                task.chatId.toInt() + task.executeAtTimestamp.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    // Fallback if exact alarm permission was revoked after reboot
                    alarmManager.set(AlarmManager.RTC_WAKEUP, task.executeAtTimestamp, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.executeAtTimestamp,
                        pendingIntent
                    )
                }
                Log.i(TAG, "Successfully restored exact alarm for chat ${task.chatId} at ${task.executeAtTimestamp}")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException while restoring exact alarm: ${e.localizedMessage}")
            }
        }
    }
}
