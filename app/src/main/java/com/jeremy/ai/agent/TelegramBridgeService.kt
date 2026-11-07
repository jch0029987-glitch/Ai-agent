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
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Telegram Agent Bot Running..."))
        showToast("TelegramBridgeService Created!")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        if (botToken.isBlank() || botToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
            showToast("ERROR: Telegram Bot Token is blank!")
            return START_NOT_STICKY
        }

        showToast("Telegram Polling Starting...")
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
                    showToast("Polling Crash: ${e.localizedMessage}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollUpdates() {
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        val urlString = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
        
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

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
                        val chatId = message.getJSONObject("chat").getLong("id").toString()
                        val text = message.optString("text", "")

                        showToast("Got Telegram Msg: $text")

                        if (text.isNotBlank()) {
                            processRemoteCommand(chatId, text)
                        }
                    }
                }
            }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown"
            showToast("Telegram HTTP Error $responseCode: $err")
        }
    }

    private fun processRemoteCommand(chatId: String, prompt: String) {
        serviceScope.launch {
            try {
                val finalOutput = AgentBridge.processPrompt(prompt) { token -> }
                val responseText = if (!finalOutput.isNullOrBlank()) finalOutput else "✅ Task completed."
                sendTelegramResponse(chatId, responseText)
            } catch (e: Exception) {
                sendTelegramResponse(chatId, "Agent Error: ${e.localizedMessage}")
            }
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
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            showToast("Failed to send reply: ${e.localizedMessage}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
