package com.jeremy.ai.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TermuxPluginBridge {

    suspend fun executeInTermux(
        context: Context,
        command: String,
        timeoutSeconds: Long = 30L
    ): String = withContext(Dispatchers.IO) {
        if (ShellSessionManager.appContext == null) {
            ShellSessionManager.appContext = context.applicationContext
        }

        val result = ShellSessionManager.executeCommand(
            command = command,
            timeoutSeconds = timeoutSeconds
        )

        result.take(4000).ifBlank { "Command executed in Termux with no output." }
    }
}

