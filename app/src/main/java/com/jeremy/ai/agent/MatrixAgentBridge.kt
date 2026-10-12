package com.jeremy.ai.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MatrixAgentBridge(
    private val context: Context,
    private val agentManager: AgentManager,
    private val homeserverUrl: String = "http://127.0.0.1:8082",
    private val accessToken: String = "syt_YWk_RjfwAZHdioPTGicpEfMU_3g4uhb",
    private val botUserId: String = "@ai:localhost"
) {
    private val TAG = "MatrixAgentBridge"
    private val bridgeScope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private var nextBatchToken: String? = null

    private val openAiCompletionUrl = "http://127.0.0.1:8080/v1/chat/completions"

    fun startListening() {
        if (isRunning) return
        isRunning = true

        bridgeScope.launch {
            Log.i(TAG, "Matrix Agent Bridge started, syncing with $homeserverUrl...")
            while (isActive && isRunning) {
                try {
                    pollMatrixSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Matrix sync error: ${e.localizedMessage}")
                    delay(5000)
                }
            }
        }
    }

    fun stopListening() {
        isRunning = false
        Log.i(TAG, "Matrix Agent Bridge stopped.")
    }

    private suspend fun pollMatrixSync() {
        val syncUrl = buildString {
            append("$homeserverUrl/_matrix/client/v3/sync?timeout=30000")
            if (nextBatchToken != null) {
                append("&since=$nextBatchToken")
            }
        }

        val url = URL(syncUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 35000
            readTimeout = 35000
        }

        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            
            nextBatchToken = json.optString("next_batch", null)

            val rooms = json.optJSONObject("rooms")?.optJSONObject("join") ?: return
            for (roomId in rooms.keys()) {
                val roomObj = rooms.getJSONObject(roomId)
                val timeline = roomObj.optJSONObject("timeline") ?: continue
                val events = timeline.optJSONArray("events") ?: continue

                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    handleMatrixEvent(roomId, event)
                }
            }
        }
    }

    private suspend fun handleMatrixEvent(roomId: String, event: JSONObject) {
        val type = event.optString("type")
        if (type != "m.room.message") return

        val sender = event.optString("sender")
        if (sender == botUserId) return

        // SAFETY FILTER: Ignore Google Messages / SMS bridge senders or puppets so the bot doesn't auto-reply to texts
        if (sender.contains("sms") || sender.contains("puppet") || sender.contains("messenger")) {
            Log.i(TAG, "Skipping bridged SMS message from $sender")
            return
        }

        val content = event.optJSONObject("content") ?: return
        if (content.optString("msgtype") != "m.text") return

        val body = content.optString("body", "").trim()
        if (body.isEmpty()) return

        // Optional safety: Only respond if explicitly mentioned or if it's a direct chat room with the bot
        // if (!body.contains("@ai") && !roomId.startsWith("!")) return

        Log.i(TAG, "Matrix message from $sender: $body")

        val initialEventId = sendMatrixMessage(roomId, "Thinking...") ?: return

        val fullResponse = StringBuilder()
        var lastUpdateTime = System.currentTimeMillis()

        agentManager.promptAgent(body) { token ->
            fullResponse.append(token)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateTime > 250) {
                lastUpdateTime = currentTime
                editMatrixMessage(roomId, initialEventId, fullResponse.toString())
            }
        }

        editMatrixMessage(roomId, initialEventId, fullResponse.toString().ifEmpty { "Done." })
    }

    private fun sendMatrixMessage(roomId: String, message: String): String? {
        try {
            val txnId = System.currentTimeMillis()
            val url = URL("$homeserverUrl/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val jsonBody = JSONObject().apply {
                put("msgtype", "m.text")
                put("body", message)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            if (connection.responseCode == 200) {
                val resp = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                return resp.optString("event_id", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send initial Matrix message: ${e.localizedMessage}")
        }
        return null
    }

    private fun editMatrixMessage(roomId: String, targetEventId: String, newText: String) {
        bridgeScope.launch(Dispatchers.IO) {
            try {
                val txnId = System.currentTimeMillis()
                val url = URL("$homeserverUrl/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val jsonBody = JSONObject().apply {
                    put("msgtype", "m.text")
                    put("body", " * $newText")
                    put("m.new_content", JSONObject().apply {
                        put("msgtype", "m.text")
                        put("body", newText)
                    })
                    put("m.relates_to", JSONObject().apply {
                        put("rel_type", "m.replace")
                        put("event_id", targetEventId)
                    })
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }
                connection.connect()
                connection.inputStream.close()
            } catch (e: Exception) {
                // Ignore minor edit network hiccups
            }
        }
    }

    suspend fun queryOpenAiStyleEndpoint(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(openAiCompletionUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer syt_YWk_RjfwAZHdioPTGicpEfMU_3g4uhb")
                doOutput = true
                connectTimeout = 3000
                readTimeout = 60000
            }

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val jsonBody = JSONObject().apply {
                put("model", "model.gguf")
                put("messages", messagesArray)
                put("temperature", 0.2)
                put("stream", false)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val rootJson = JSONObject(responseStr)
                val choices = rootJson.getJSONArray("choices")
                val firstChoice = choices.getJSONObject(0)
                val messageObj = firstChoice.getJSONObject("message")
                return@withContext messageObj.getString("content").trim()
            } else {
                return@withContext "Error: OpenAI endpoint returned HTTP ${connection.responseCode}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI endpoint query failed: ${e.localizedMessage}")
            return@withContext "Error: ${e.localizedMessage}"
        }
    }
}
