package com.jeremy.ai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jeremy.ai.backend.ChatSessionManager
import com.jeremy.ai.backend.PairingPromptManager
import com.jeremy.ai.backend.TelegramApprovalStore
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class TelegramBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var lastUpdateId = 0L
    private val activeCommandJobs = ConcurrentHashMap<String, Job>()

    private val client = HttpClient(Android) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15000L
            socketTimeoutMillis = 50000L
            requestTimeoutMillis = 50000L
        }
        engine {
            connectTimeout = 15000
            socketTimeout = 50000
        }
    }

    companion object {
        private const val TAG = "TelegramBridge"
        private const val CHANNEL_ID = "TelegramBridgeChannel"
        private const val NOTIF_ID = 1337
        private const val MAX_TELEGRAM_CHARS = 3900
        private const val LONG_POLL_TIMEOUT_SEC = 30
        private val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Telegram Agent Bot Running..."))
        Log.i(TAG, "TelegramBridgeService Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        if (botToken.isBlank() || botToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
            Log.e(TAG, "Telegram Bot Token is blank!")
            return START_NOT_STICKY
        }

        if (pollingJob?.isActive != true) {
            Log.i(TAG, "Telegram Ktor Polling Starting...")
            startPolling()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram AI Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT-LM Agent Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun isRetryableError(throwable: Throwable): Boolean = when (throwable) {
        is ConnectTimeoutException,
        is SocketTimeoutException,
        is HttpRequestTimeoutException -> true
        else -> throwable.cause?.let(::isRetryableError) == true
    }

    private suspend fun postTelegramWithRetry(
        url: String,
        jsonPayload: String,
        maxAttempts: Int = 3,
        baseDelayMs: Long = 500L
    ): HttpResponse {
        var lastResponse: HttpResponse? = null
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                val resp = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonPayload)
                    timeout {
                        connectTimeoutMillis = 15000L
                        socketTimeoutMillis = 25000L
                        requestTimeoutMillis = 25000L
                    }
                }
                lastResponse = resp

                if (resp.status.value !in RETRYABLE_STATUS_CODES || attempt == maxAttempts) {
                    return resp
                }

                val backoffMs = baseDelayMs * (1L shl (attempt - 1))
                Log.w(TAG, "Telegram HTTP ${resp.status.value} on $url (attempt $attempt/$maxAttempts), retrying in ${backoffMs}ms")
                delay(backoffMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val canRetry = attempt < maxAttempts && isRetryableError(e)
                if (!canRetry) throw e
                val backoffMs = baseDelayMs * (1L shl (attempt - 1))
                Log.w(TAG, "Transient error on $url (attempt $attempt/$maxAttempts): ${e.localizedMessage ?: e.javaClass.simpleName}, retrying in ${backoffMs}ms")
                delay(backoffMs)
            }
        }

        if (lastResponse != null) return lastResponse
        throw lastError ?: IllegalStateException("Telegram request failed after $maxAttempts attempts")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollUpdates()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HttpRequestTimeoutException) {
                    // Normal when Telegram holds long-poll open for the full timeout window with no new updates
                } catch (e: Exception) {
                    if (isRetryableError(e)) {
                        Log.w(TAG, "Transient polling timeout/network hiccup: ${e.localizedMessage ?: e.javaClass.simpleName}")
                        delay(1500)
                    } else {
                        Log.e(TAG, "Polling exception: ${e.localizedMessage}", e)
                        delay(5000)
                    }
                }
            }
        }
    }

    private suspend fun pollUpdates() {
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        val allowedUserId = BuildConfig.TELEGRAM_ALLOWED_USER_ID

        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=$LONG_POLL_TIMEOUT_SEC"

        val response: HttpResponse = client.get(url) {
            timeout {
                connectTimeoutMillis = 15000L
                requestTimeoutMillis = 50000L
                socketTimeoutMillis = 50000L
            }
        }

        if (response.status == HttpStatusCode.OK) {
            val responseText = response.bodyAsText()
            val json = JSONObject(responseText)
            if (json.optBoolean("ok")) {
                val results = json.getJSONArray("result")
                for (i in 0 until results.length()) {
                    val update = results.getJSONObject(i)
                    lastUpdateId = update.getLong("update_id")

                    if (update.has("callback_query")) {
                        handleCallbackQuery(update.getJSONObject("callback_query"), botToken, allowedUserId)
                    }

                    if (update.has("message")) {
                        val message = update.getJSONObject("message")
                        val fromUser = message.optJSONObject("from")
                        val senderId = fromUser?.optLong("id")?.toString() ?: ""
                        val chatIdLong = message.getJSONObject("chat").getLong("id")
                        val chatId = chatIdLong.toString()
                        val text = message.optString("text", "")

                        Log.i(TAG, "Received message: '$text' from sender: $senderId")

                        if (allowedUserId.isNotBlank() && senderId != allowedUserId) {
                            sendTelegramResponse(chatId, "Unauthorized user ID: $senderId", botToken)
                            continue
                        }

                        if (text.isNotBlank()) {
                            routeTextMessage(chatIdLong, text, botToken, message.getLong("message_id"))
                        }
                    }
                }
            } else {
                Log.e(TAG, "Telegram API returned ok=false: $responseText")
            }
        } else {
            val errBody = response.bodyAsText()
            if (response.status.value in RETRYABLE_STATUS_CODES) {
                Log.w(TAG, "Telegram transient HTTP ${response.status.value} on getUpdates: $errBody")
                delay(1500)
            } else {
                Log.e(TAG, "Telegram HTTP Error ${response.status.value}: $errBody")
                if (response.status == HttpStatusCode.Conflict) {
                    delay(5000)
                }
            }
        }
    }

    private suspend fun handleCallbackQuery(
        callbackQuery: JSONObject,
        botToken: String,
        allowedUserId: String
    ) {
        val callbackId = callbackQuery.optString("id", "")
        val fromUser = callbackQuery.optJSONObject("from")
        val senderId = fromUser?.optLong("id")?.toString() ?: ""
        val data = callbackQuery.optString("data", "").trim()
        val chatIdLong = callbackQuery.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")

        Log.i(TAG, "Received callback_query data='$data' from sender=$senderId")

        if (allowedUserId.isNotBlank() && senderId != allowedUserId) {
            if (callbackId.isNotBlank()) {
                answerCallbackQuery(botToken, callbackId, "Unauthorized user", showAlert = true)
            }
            return
        }

        val (approved, taskId) = when {
            data.startsWith("approve:") -> true to data.removePrefix("approve:").trim()
            data.startsWith("deny:") -> false to data.removePrefix("deny:").trim()
            else -> {
                if (callbackId.isNotBlank()) {
                    answerCallbackQuery(botToken, callbackId, "Unhandled action")
                }
                return
            }
        }

        val future = TelegramApprovalStore.pendingApprovals.remove(taskId)
        val completed = future?.complete(approved) == true

        if (callbackId.isNotBlank()) {
            val toastText = when {
                completed && approved -> "Approved"
                completed && !approved -> "Denied"
                else -> "Approval expired or already handled"
            }
            answerCallbackQuery(botToken, callbackId, toastText)
        }

        if (chatIdLong != null && chatIdLong != 0L) {
            val statusMsg = when {
                completed && approved -> "✅ Approved action (`$taskId`)."
                completed && !approved -> "⛔ Denied action (`$taskId`)."
                else -> "⚠️ No pending approval found for `$taskId`."
            }
            sendTelegramResponse(chatIdLong.toString(), statusMsg, botToken)
        }
    }

    private suspend fun answerCallbackQuery(
        botToken: String,
        callbackQueryId: String,
        text: String,
        showAlert: Boolean = false
    ) {
        try {
            val payload = JSONObject().apply {
                put("callback_query_id", callbackQueryId)
                put("text", text)
                put("show_alert", showAlert)
            }.toString()

            val resp = postTelegramWithRetry(
                url = "https://api.telegram.org/bot$botToken/answerCallbackQuery",
                jsonPayload = payload,
                maxAttempts = 2,
                baseDelayMs = 400L
            )
            if (!resp.status.isSuccess()) {
                Log.w(TAG, "answerCallbackQuery failed (${resp.status.value}): ${resp.bodyAsText()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to answer callback query", e)
        }
    }

    private fun routeTextMessage(chatIdLong: Long, rawText: String, botToken: String, messageId: Long) {
        val chatId = chatIdLong.toString()
        val trimmed = rawText.trim()
        ChatSessionManager.lastActiveTelegramChatId = chatIdLong

        if (PairingPromptManager.submitPairing(chatId, trimmed)) {
            serviceScope.launch {
                sendTelegramResponse(chatId, "Pairing code/port received. Running `adb pair` and retrying...", botToken)
            }
            return
        }

        if (trimmed.equals("/cancel", ignoreCase = true)) {
            val running = activeCommandJobs.remove(chatId)
            serviceScope.launch {
                if (running != null && running.isActive) {
                    running.cancel()
                    sendTelegramResponse(chatId, "Cancelled active task.", botToken)
                } else {
                    sendTelegramResponse(chatId, "No active task to cancel.", botToken)
                }
            }
            return
        }

        if (trimmed.equals("/reset", ignoreCase = true)) {
            ChatSessionManager.clearSession("telegram-$chatId")
            serviceScope.launch {
                sendTelegramResponse(chatId, "Session history cleared.", botToken)
            }
            return
        }

        if (trimmed.startsWith("/remember", ignoreCase = true)) {
            val note = trimmed.removePrefix("/remember").trim()
            val manager = AgentBridge.agentManager
            serviceScope.launch {
                if (manager == null) {
                    sendTelegramResponse(chatId, "Error: AgentManager not initialized.", botToken)
                    return@launch
                }
                if (note.isEmpty()) {
                    sendTelegramResponse(chatId, "Usage: `/remember <fact or note>`", botToken)
                    return@launch
                }
                val reply = manager.rememberNote(note)
                sendTelegramResponse(chatId, reply, botToken)
            }
            return
        }

        if (trimmed.equals("/memory", ignoreCase = true)) {
            val manager = AgentBridge.agentManager
            serviceScope.launch {
                if (manager == null) {
                    sendTelegramResponse(chatId, "Error: AgentManager not initialized.", botToken)
                    return@launch
                }
                val memoryDump = manager.readMemoryFiles()
                sendTelegramResponse(chatId, memoryDump, botToken)
            }
            return
        }

        if (trimmed.equals("/backend", ignoreCase = true) || trimmed.equals("/model", ignoreCase = true)) {
            val status = AgentBridge.agentManager?.getBackendStatus() ?: "Error: AgentManager not initialized."
            serviceScope.launch {
                sendTelegramResponse(
                    chatId,
                    "$status\n\nUse `/backend ollama` or `/backend litert`, or `/model qwen2.5-coder:3b`.",
                    botToken
                )
            }
            return
        }

        if (trimmed.startsWith("/backend ", ignoreCase = true)) {
            val arg = trimmed.removePrefix("/backend").trim().lowercase()
            val manager = AgentBridge.agentManager
            serviceScope.launch {
                if (manager == null) {
                    sendTelegramResponse(chatId, "Error: AgentManager not initialized.", botToken)
                    return@launch
                }
                val statusMsg = when {
                    arg.startsWith("ollama") -> {
                        val parts = arg.split(Regex("\\s+"), limit = 2)
                        manager.switchToOllama(modelName = parts.getOrNull(1))
                    }
                    arg.startsWith("litert") || arg.startsWith("gemma") -> {
                        manager.switchToLiteRtLm()
                    }
                    else -> "Unknown backend `$arg`. Use `ollama` or `litert`."
                }
                sendTelegramResponse(chatId, statusMsg, botToken)
            }
            return
        }

        if (trimmed.startsWith("/model ", ignoreCase = true)) {
            val modelName = trimmed.removePrefix("/model").trim()
            val manager = AgentBridge.agentManager
            serviceScope.launch {
                if (manager == null) {
                    sendTelegramResponse(chatId, "Error: AgentManager not initialized.", botToken)
                    return@launch
                }
                val statusMsg = if (modelName.endsWith(".litertlm", ignoreCase = true)) {
                    manager.switchToLiteRtLm(modelName)
                } else {
                    manager.switchToOllama(modelName = modelName)
                }
                sendTelegramResponse(chatId, statusMsg, botToken)
            }
            return
        }

        if (trimmed.startsWith("/approve", ignoreCase = true) || trimmed.startsWith("/deny", ignoreCase = true)) {
            val isApprove = trimmed.startsWith("/approve", ignoreCase = true)
            val jobId = trimmed.removePrefix(if (isApprove) "/approve" else "/deny").trim()
            val completed = if (jobId.isNotEmpty()) {
                TelegramApprovalStore.pendingApprovals.remove(jobId)?.complete(isApprove) != null
            } else {
                false
            }
            serviceScope.launch {
                sendTelegramResponse(
                    chatId,
                    if (completed) {
                        if (isApprove) "Approved job `$jobId`." else "Denied job `$jobId`."
                    } else {
                        "No pending approval found for job `$jobId`."
                    },
                    botToken
                )
            }
            return
        }

        processRemoteCommand(chatId, rawText, botToken, messageId)
    }

    private fun processRemoteCommand(chatId: String, prompt: String, botToken: String, messageId: Long) {
        activeCommandJobs[chatId]?.cancel()
        val job = serviceScope.launch {
            sendChatAction(chatId, "typing", botToken)
            sendTelegramResponse(chatId, "Processing request...", botToken)

            try {
                val finalOutput = AgentBridge.processPromptWithTelegramUpdates(
                    prompt = prompt,
                    botToken = botToken,
                    chatId = chatId.toLong(),
                    messageId = messageId
                )

                val responseText = if (finalOutput.isBlank()) "Task completed." else finalOutput
                sendTelegramResponse(chatId, responseText, botToken)
            } catch (e: CancellationException) {
                sendTelegramResponse(chatId, "Task cancelled.", botToken)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution error", e)
                sendTelegramResponse(chatId, "Agent Error: ${e.localizedMessage ?: e.javaClass.simpleName}", botToken)
            } finally {
                activeCommandJobs.remove(chatId)
            }
        }
        activeCommandJobs[chatId] = job
    }

    private suspend fun sendChatAction(chatId: String, action: String, botToken: String = BuildConfig.TELEGRAM_BOT_TOKEN) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("action", action)
            }.toString()

            val resp = postTelegramWithRetry(
                url = "https://api.telegram.org/bot$botToken/sendChatAction",
                jsonPayload = payload,
                maxAttempts = 2,
                baseDelayMs = 400L
            )
            if (!resp.status.isSuccess()) {
                Log.w(TAG, "sendChatAction failed (${resp.status.value}): ${resp.bodyAsText()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat action", e)
        }
    }

    private suspend fun sendTelegramResponse(chatId: String, text: String, botToken: String = BuildConfig.TELEGRAM_BOT_TOKEN) {
        try {
            val chunks = text.chunked(MAX_TELEGRAM_CHARS)
            for (chunk in chunks) {
                val mdPayload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", chunk)
                    put("parse_mode", "Markdown")
                }.toString()

                val resp = postTelegramWithRetry(
                    url = "https://api.telegram.org/bot$botToken/sendMessage",
                    jsonPayload = mdPayload
                )

                if (resp.status.isSuccess()) {
                    continue
                }

                val errBody = resp.bodyAsText()
                if (resp.status == HttpStatusCode.BadRequest && errBody.contains("parse entities", ignoreCase = true)) {
                    val plainPayload = JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", chunk)
                    }.toString()
                    val retryResp = postTelegramWithRetry(
                        url = "https://api.telegram.org/bot$botToken/sendMessage",
                        jsonPayload = plainPayload
                    )
                    if (!retryResp.status.isSuccess()) {
                        Log.e(TAG, "sendMessage plain retry failed (${retryResp.status.value}): ${retryResp.bodyAsText()}")
                    }
                } else {
                    Log.e(TAG, "sendMessage failed (${resp.status.value}): $errBody")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        activeCommandJobs.values.forEach { it.cancel() }
        activeCommandJobs.clear()
        serviceScope.cancel()
        client.close()
        super.onDestroy()
    }
}
