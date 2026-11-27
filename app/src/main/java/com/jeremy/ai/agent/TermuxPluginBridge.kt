package com.jeremy.ai.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TermuxPluginBridge {

    suspend fun executeInTermux(
        context: Context,
        command: String,
        chatId: Long = ChatSessionManager.lastActiveTelegramChatId,
        timeoutSeconds: Long = 30L
    ): String = withContext(Dispatchers.IO) {
        val targetContext = ShellSessionManager.safeAppContext(context) ?: context.applicationContext

        val result = ShellSessionManager.executeCommand(
            context = targetContext,
            chatId = chatId,
            command = command,
            timeoutSeconds = timeoutSeconds
        )

        result.take(4000).ifBlank { "Command executed in Termux with no output." }
    }
}
