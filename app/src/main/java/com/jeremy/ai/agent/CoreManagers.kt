package com.jeremy.ai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ==========================================
// 1. Telegram Approval Store
// ==========================================
object TelegramApprovalStore {
    val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
}

// ==========================================
// 2. Chat Session Manager
// ==========================================
object ChatSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    fun getHistory(clientId: String) = sessions.getOrPut(clientId) { mutableListOf() }
    fun clearSession(clientId: String) { sessions.remove(clientId) }
    fun appendMessage(clientId: String, role: String, content: String) {
        getHistory(clientId).add(Pair(role, content))
    }
}

// ==========================================
// 3. Shell Session & Termux Bridge Manager
// ==========================================
object ShellSessionManager {
    lateinit var appContext: Context

    private val workingDirectories = ConcurrentHashMap<Long, File>()
    private val pendingResults = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val atomicId = AtomicInteger(0)

    fun executeCommand(context: Context, chatId: Long, command: String): String {
        val currentDir = workingDirectories.getOrPut(chatId) { File("/data/data/com.termux/files/home") }

        if (command.trim().startsWith("cd ")) {
            val targetDir = command.trim().removePrefix("cd").trim()
            val newDir = if (targetDir.startsWith("/")) File(targetDir) else File(currentDir, targetDir)
            return if (newDir.exists() && newDir.isDirectory) {
                workingDirectories[chatId] = newDir.canonicalFile
                "Directory changed to: ${newDir.canonicalPath}"
            } else {
                "Error: Directory not found: $targetDir"
            }
        }

        val executionId = atomicId.incrementAndGet()
        val future = CompletableFuture<String>()
        pendingResults[executionId] = future

        try {
            val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                putExtra("execution_id", executionId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                executionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", currentDir.absolutePath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }

            context.startForegroundService(intent)

            return try {
                future.get(5, TimeUnit.SECONDS) ?: "Command executed with no output."
            } catch (e: java.util.concurrent.TimeoutException) {
                "Error: Termux execution timed out waiting for callback."
            } finally {
                pendingResults.remove(executionId)
            }
        } catch (e: Exception) {
            pendingResults.remove(executionId)
            return "Termux Intent dispatch error: ${e.localizedMessage}"
        }
    }

    fun handleResult(executionId: Int, intent: Intent, bundle: Bundle?) {
        val future = pendingResults.remove(executionId) ?: return

        val allExtras = mutableMapOf<String, Any?>()
        bundle?.keySet()?.forEach { key -> allExtras[key] = bundle.get(key) }
        intent.extras?.keySet()?.forEach { key -> allExtras["intent_$key"] = intent.extras?.get(key) }

        val stdout = bundle?.getString("stdout")
            ?: bundle?.getString("com.termux.RUN_COMMAND_STDOUT")
            ?: bundle?.getString("com.termux.plugin.result.bundle.stdout")
            ?: intent.getStringExtra("stdout")
            ?: intent.getStringExtra("com.termux.RUN_COMMAND_STDOUT")
            ?: allExtras.entries.firstOrNull { 
                it.key.contains("stdout", true) || it.key.contains("output", true) 
            }?.value?.toString()
            ?: allExtras.entries.firstOrNull { it.value is String && (it.value as String).length > 5 }?.value?.toString()
            ?: "Command completed successfully."

        future.complete(stdout.take(4000))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val executionId = intent.getIntExtra("execution_id", -1)
        val bundle = intent.getBundleExtra("com.termux.plugin.result.bundle") 
            ?: intent.getBundleExtra("result") 
            ?: intent.extras
        ShellSessionManager.handleResult(executionId, intent, bundle)
    }
}

// ==========================================
// 4. Scheduled Tasks, Alarms & Cron Manager
// ==========================================
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
            } catch (e: Exception) {}
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
