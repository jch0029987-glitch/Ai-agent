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
        private val BOT_TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
        private val ALLOWED_USER_ID = BuildConfig.TELEGRAM_ALLOWED_USER_ID
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Telegram Agent Bot Running..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BOT_TOKEN.isBlank() || BOT_TOKEN == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
            Log.e(TAG, "Telegram BOT_TOKEN is missing or unconfigured in local.properties.")
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting Telegram Native Polling Service...")
        startPolling()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram AI Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local Telegram AI agent bridge active in background"
            }
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
                    Log.e(TAG, "Polling error: ${e.localizedMessage}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollUpdates() {
        val urlString = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 35000
        conn.readTimeout = 35000

        if (conn.responseCode == 200) {
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

                        if (ALLOWED_USER_ID.isNotBlank() && senderId != ALLOWED_USER_ID) {
                            sendTelegramResponse(
                                chatId, 
                                "⚠️ Blocked. Your Telegram Sender ID is `$senderId`, but `ALLOWED_USER_ID` is set to `$ALLOWED_USER_ID`."
                            )
                            continue
                        }

                        if (text.isNotBlank()) {
                            processRemoteCommand(chatId, text)
                        }
                    }
                }
            }
        }
    }

    private fun processRemoteCommand(chatId: String, prompt: String) {
        serviceScope.launch {
            sendChatAction(chatId, "typing")
            sendTelegramResponse(chatId, "⚙️ Processing request...")

            try {
                sendTelegramResponse(chatId, "🔄 Calling AgentBridge.processPrompt()...")

                val finalOutput = AgentBridge.processPrompt(prompt) { token -> 
                    // Optional token updates
                }

                sendTelegramResponse(chatId, "📥 AgentBridge finished. Output length: ${finalOutput.length}")

                val responseText = if (!finalOutput.isNullOrBlank()) finalOutput else "✅ Task completed."
                sendTelegramResponse(chatId, responseText)

            } catch (e: Exception) {
                Log.e(TAG, "Error executing prompt", e)
                val errorDetails = e.localizedMessage ?: e.javaClass.simpleName
                val stackTop = e.stackTrace.firstOrNull()?.toString() ?: "Unknown line"
                sendTelegramResponse(chatId, "🔴 Agent Error:\n`$errorDetails`\n\nAt: `$stackTop`")
            }
        }
    }

    private fun sendChatAction(chatId: String, action: String) {
        try {
            val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendChatAction")
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
            val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
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
            Log.e(TAG, "Failed to send response", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
