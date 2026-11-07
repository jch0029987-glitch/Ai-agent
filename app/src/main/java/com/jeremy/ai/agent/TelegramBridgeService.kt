package com.jeremy.ai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelegramBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId = 0L

    companion object {
        private const val TAG = "TelegramBridge"
        private const val CHANNEL_ID = "TelegramBridgeChannel"
        private const val NOTIF_ID = 1337
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
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
            showToast("ERROR: Telegram Bot Token is blank!")
            return START_NOT_STICKY
        }

        Log.i(TAG, "Telegram Polling Starting...")
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
        
        val urlString = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 35000
        conn.readTimeout = 35000

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
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

                        // Security check against ALLOWED_USER_ID if configured
                        if (allowedUserId.isNotBlank() && senderId != allowedUserId) {
                            sendTelegramResponse(chatId, "⚠️ Unauthorized user ID: `$senderId`")
                            continue
                        }

                        if (text.isNotBlank()) {
                            processRemoteCommand(chatId, text, botToken, update.getJSONObject("message").getLong("message_id"))
                        }
                    }
                }
            }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown"
            Log.e(TAG, "Telegram HTTP Error $responseCode: $err")
        }
    }

    private fun processRemoteCommand(chatId: String, prompt: String, botToken: String, messageId: Long) {
        serviceScope.launch {
            // 1. Send typing indicator immediately
            sendChatAction(chatId, "typing")
            sendTelegramResponse(chatId, "⚙️ Processing request...")

            try {
                // 2. Route via the dedicated Telegram webhook method in AgentBridge
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
        try {
            val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
            val url = URL("https://api.telegram.org/bot$botToken/sendChatAction")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("action", action)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat action", e)
        }
    }

    private fun sendTelegramResponse(chatId: String, text: String) {
        try {
            val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
