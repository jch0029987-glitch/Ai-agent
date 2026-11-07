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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.json.JSONObject

class TelegramBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId = 0L
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }

    companion object {
        private const val TAG = "TelegramBridge"
        private const val CHANNEL_ID = "TelegramBridgeChannel"
        private const val NOTIF_ID = 1337
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

        Log.i(TAG, "Telegram Ktor Polling Starting...")
        startPolling()
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

    private fun startPolling() {
        serviceScope.launch {
            while (isActive) {
                try {
                    pollUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling exception: ${e.localizedMessage}", e)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollUpdates() {
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        val allowedUserId = BuildConfig.TELEGRAM_ALLOWED_USER_ID
        
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
        
        val response: HttpResponse = client.get(url) {
            timeout {
                requestTimeoutMillis = 35000
                socketTimeoutMillis = 35000
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
                        val senderId = fromUser?.getLong("id")?.toString() ?: ""
                        val chatId = message.getJSONObject("chat").getLong("id").toString()
                        val text = message.optString("text", "")

                        Log.i(TAG, "Received message: '$text' from sender: $senderId")

                        if (allowedUserId.isNotBlank() && senderId != allowedUserId) {
                            sendTelegramResponse(chatId, "⚠️ Unauthorized user ID: `$senderId`")
                            continue
                        }

                        if (text.isNotBlank()) {
                            processRemoteCommand(chatId, text, botToken, message.getLong("message_id"))
                        }
                    }
                }
            }
        } else {
            Log.e(TAG, "Telegram HTTP Error ${response.status.value}: ${response.bodyAsText()}")
        }
    }

    private fun processRemoteCommand(chatId: String, prompt: String, botToken: String, messageId: Long) {
        serviceScope.launch {
            sendChatAction(chatId, "typing")
            sendTelegramResponse(chatId, "⚙️ Processing request...")

            try {
                val finalOutput = AgentBridge.processPromptWithTelegramUpdates(
                    prompt = prompt,
                    botToken = botToken,
                    chatId = chatId.toLong(),
                    messageId = messageId
                )

                val responseText = if (!finalOutput.isNullOrBlank()) finalOutput else "✅ Task completed."
                sendTelegramResponse(chatId, responseText)

            } catch (e: Exception) {
                Log.e(TAG, "Agent execution error", e)
                sendTelegramResponse(chatId, "🔴 Agent Error: ${e.localizedMessage}")
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

                client.post("https://api.telegram.org/bot$botToken/sendChatAction") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
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
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
                }.toString()

                client.post("https://api.telegram.org/bot$botToken/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        client.close()
        serviceScope.cancel()
    }
}
