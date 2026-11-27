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

class TelegramBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var lastUpdateId = 0L

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

                    if (update.has("message")) {
                        val message = update.getJSONObject("message")
                        val fromUser = message.optJSONObject("from")
                        val senderId = fromUser?.optLong("id")?.toString() ?: ""
                        val chatIdLong = message.getJSONObject("chat").getLong("id")
                        val chatId = chatIdLong.toString()
                        val text = message.optString("text", "")

                        Log.i(TAG, "Received message: '$text' from sender: $senderId")

                        if (allowedUserId.isNotBlank() && senderId != allowedUserId) {
                            sendTelegramResponse(chatId, "Unauthorized user ID: $senderId")
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

    private fun routeTextMessage(chatIdLong: Long, rawText: String, botToken: String, messageId: Long) {
        val chatId = chatIdLong.toString()
        val trimmed = rawText.trim()
        ChatSessionManager.lastActiveTelegramChatId = chatIdLong

        if (PairingPromptManager.submitPairing(chatId, trimmed)) {
            sendTelegramResponse(chatId, "Pairing code/port received. Running `adb pair` and retrying...")
            return
        }

        if (trimmed.equals("/reset", ignoreCase = true)) {
            ChatSessionManager.clearSession("telegram-$chatId")
            sendTelegramResponse(chatId, "Session history cleared.")
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
            sendTelegramResponse(
                chatId,
                if (completed) {
                    if (isApprove) "Approved job `$jobId`." else "Denied job `$jobId`."
                } else {
                    "No pending approval found for job `$jobId`."
                }
            )
            return
        }

        processRemoteCommand(chatId, rawText, botToken, messageId)
    }

    private fun processRemoteCommand(chatId: String, prompt: String, botToken: String, messageId: Long) {
        serviceScope.launch {
            sendChatAction(chatId, "typing")
            sendTelegramResponse(chatId, "Processing request...")

            try {
                val finalOutput = AgentBridge.processPromptWithTelegramUpdates(
                    prompt = prompt,
                    botToken = botToken,
                    chatId = chatId.toLong(),
                    messageId = messageId
                )

                val responseText = if (!finalOutput.isNullOrBlank()) finalOutput else "Task completed."
                sendTelegramResponse(chatId, responseText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution error", e)
                sendTelegramResponse(chatId, "Agent Error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun sendChatAction(chatId: String, action: String) {
        serviceScope.launch {
            try {
                val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send chat action", e)
            }
        }
    }

    private fun sendTelegramResponse(chatId: String, text: String) {
        serviceScope.launch {
            try {
                val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        client.close()
        super.onDestroy()
    }
}
