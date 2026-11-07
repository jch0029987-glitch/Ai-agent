package com.jeremy.ai.backend

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jeremy.ai.agent.AgentBridge
import com.jeremy.ai.agent.BuildConfig

class AgentWorkRequest(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prompt = inputData.getString("prompt") ?: return Result.failure()
        val chatId = inputData.getLong("chat_id", 0L)
        val botToken = inputData.getString("bot_token") ?: BuildConfig.TELEGRAM_BOT_TOKEN

        if (chatId == 0L || botToken.isBlank()) {
            return Result.failure()
        }

        try {
            // Send initial trigger notification
            val statusMsgId = sendTelegramMessage(botToken, chatId, "⏰ *WorkManager Task Triggered:*\n`${prompt}`")
            
            // Execute the autonomous agent loop safely in the background
            val response = AgentBridge.processPromptWithTelegramUpdates(
                prompt = prompt,
                botToken = botToken,
                chatId = chatId,
                messageId = statusMsgId ?: 0L
            )

            if (statusMsgId != null) {
                editTelegramMessage(botToken, chatId, statusMsgId, "✅ *Task Completed*\n\n$response")
            } else {
                sendTelegramMessage(botToken, chatId, "✅ *Task Completed*\n\n$response")
            }

            return Result.success()
        } catch (e: Exception) {
            sendTelegramMessage(botToken, chatId, "❌ *Task Failed:* ${e.localizedMessage}")
            return Result.retry()
        }
    }
}
