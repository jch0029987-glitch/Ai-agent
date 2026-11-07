package com.jeremy.ai.agent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelegramBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId = 0L

    companion object {
        private const val TAG = "TelegramBridge"
        private val BOT_TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
        private val ALLOWED_USER_ID = BuildConfig.TELEGRAM_ALLOWED_USER_ID
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BOT_TOKEN.isBlank() || BOT_TOKEN == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
            Log.e(TAG, "Telegram BOT_TOKEN is missing or unconfigured in local.properties. Service stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting Telegram Remote Control Service with Bot Token...")
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        serviceScope.launch {
            while (isActive) {
                try {
                    pollUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.localizedMessage}", e)
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

                        Log.i(TAG, "Received message from sender $senderId: $text")

                        if (ALLOWED_USER_ID.isNotBlank() && senderId != ALLOWED_USER_ID) {
                            sendTelegramResponse(chatId, "⚠️ Unauthorized user. Execution blocked.")
                            continue
                        }

                        if (text.isNotBlank()) {
                            processRemoteCommand(chatId, text)
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "Telegram getUpdates failed with response code: ${conn.responseCode}")
        }
    }

    private fun processRemoteCommand(chatId: String, prompt: String) {
        serviceScope.launch {
            sendTelegramResponse(chatId, "🤖 Agent processing prompt: \"$prompt\"...")

            try {
                val finalOutput = AgentBridge.processPrompt(prompt) { token ->
                    // Optional token streaming
                }

                val responseText = if (!finalOutput.isNullOrBlank()) finalOutput else "✅ Task completed."
                Log.i(TAG, "Agent finished. Sending response to chat $chatId: $responseText")
                sendTelegramResponse(chatId, responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing prompt through AgentBridge", e)
                sendTelegramResponse(chatId, "🔴 Agent Error: ${e.localizedMessage}")
            }
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
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                Log.e(TAG, "Failed to send Telegram message. Code: $responseCode, Body: $errBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send response back to Telegram", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
