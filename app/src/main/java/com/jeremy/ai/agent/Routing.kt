package com.jeremy.ai.backend

import com.jeremy.ai.agent.AgentBridge
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import android.os.Environment
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import com.jeremy.ai.BuildConfig
// ==========================================
// 1. OpenAI Spec Data Models
// ==========================================

@Serializable
data class OpenAIMessage(
    val role: String, // "system", "user", or "assistant"
    val content: String
)

@Serializable
data class OpenAIChatRequest(
    val model: String? = "gemma-4-abliterated",
    val messages: List<OpenAIMessage>,
    val stream: Boolean? = false,
    val temperature: Float? = 0.7f,
    val user: String? = null
)

@Serializable
data class OpenAIChoice(
    val index: Int = 0,
    val message: OpenAIMessage? = null,
    val delta: OpenAIDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class OpenAIChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "gemma-4-abliterated",
    val choices: List<OpenAIChoice>
)

@Serializable
data class OpenAIModelObject(
    val id: String,
    val `object`: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val owned_by: String = "local-pixel9"
)

@Serializable
data class OpenAIModelListResponse(
    val `object`: String = "list",
    val data: List<OpenAIModelObject>
)

// ==========================================
// 2. Legacy / Native Client Models
// ==========================================

@Serializable
data class ClientMessage(
    val clientId: String = "default-client",
    val deviceType: String? = "phone", // "web", "phone", or "termux-cli"
    val prompt: String = "",
    val cwd: String? = null,
    val gitContext: String? = null,
    val stdinContent: String? = null,
    val resetSession: Boolean = false
)

@Serializable
data class ServerResponse(
    val token: String,
    val isFinished: Boolean
)

// ==========================================
// 3. Telegram Bot DTO Models
// ==========================================

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("callback_query") val callbackQuery: TelegramCallbackQuery? = null
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TelegramChat,
    val text: String? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String
)

@Serializable
data class TelegramCallbackQuery(
    val id: String,
    val data: String? = null,
    val message: TelegramMessage? = null
)

// ==========================================
// 4. Approval & Session Stores
// ==========================================

object TelegramApprovalStore {
    val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
}

object ChatSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    fun getHistory(clientId: String): MutableList<Pair<String, String>> {
        return sessions.getOrPut(clientId) { mutableListOf() }
    }

    fun clearSession(clientId: String) {
        sessions.remove(clientId)
    }

    fun appendMessage(clientId: String, role: String, content: String) {
        getHistory(clientId).add(Pair(role, content))
    }
}

// ==========================================
// 5. Utility: Scan Local Model Files & Telegram API Helpers
// ==========================================

fun getAvailableModels(): List<OpenAIModelObject> {
    val modelList = mutableListOf<OpenAIModelObject>()
    try {
        val externalStorage = Environment.getExternalStorageDirectory()
        val modelsDir = File(externalStorage, "Download/models")
        val downloadDir = File(externalStorage, "Download")

        listOf(modelsDir, downloadDir).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".litertlm", ignoreCase = true) || file.name.endsWith(".task", ignoreCase = true))
                }?.forEach { modelFile ->
                    modelList.add(OpenAIModelObject(id = modelFile.name))
                }
            }
        }
    } catch (e: Exception) {
        // Fallback default
    }

    if (modelList.isEmpty()) {
        modelList.add(OpenAIModelObject(id = "gemma-4-abliterated"))
    }
    return modelList
}

private fun sendTelegramMessage(botToken: String, chatId: Long, text: String): Long? {
    return try {
        val url = java.net.URL("https://api.telegram.org/bot$botToken/sendMessage")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
        val payload = "{\"chat_id\": $chatId, \"text\": \"$escapedText\", \"parse_mode\": \"Markdown\"}"
        conn.outputStream.write(payload.toByteArray())

        if (conn.responseCode == 200) {
            val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
            val regex = "\"message_id\":(\\d+)".toRegex()
            regex.find(responseStr)?.groupValues?.get(1)?.toLongOrNull()
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun editTelegramMessage(botToken: String, chatId: Long, messageId: Long, newText: String) {
    try {
        val url = java.net.URL("https://api.telegram.org/bot$botToken/editMessageText")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val escapedText = newText.replace("\"", "\\\"").replace("\n", "\\n")
        val payload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"$escapedText\", \"parse_mode\": \"Markdown\"}"
        conn.outputStream.write(payload.toByteArray())
        conn.responseCode
    } catch (e: Exception) {
        // Handle gracefully
    }
}

// ==========================================
// 6. Ktor Routing Configuration
// ==========================================

fun Application.configureRouting() {
    val activeConnections = Collections.synchronizedSet(hashSetOf<WebSocketSession>())
    // Pull Telegram Bot API Token directly from local.properties via BuildConfig
    val telegramBotToken = com.jeremy.ai.BuildConfig.TELEGRAM_BOT_TOKEN

    routing {
        // --- Root Landing & Health Endpoints ---
        get("/") {
            call.respondText(
                "Ai-agent Local OpenAI Server is Online\n" +
                        "Endpoints:\n" +
                        "- GET /health\n" +
                        "- GET /v1/models\n" +
                        "- POST /v1/chat/completions (OpenAI Compatible)\n" +
                        "- POST /api/agent/stream (Termux SSE)\n" +
                        "- POST /api/telegram/webhook (Telegram Bot)\n" +
                        "- WS /ws/agent",
                ContentType.Text.Plain
            )
        }

        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

        // --- OpenAI Standard API Endpoints ---
        get("/v1/models") {
            call.respond(OpenAIModelListResponse(data = getAvailableModels()))
        }

        post("/v1/chat/completions") {
            try {
                val request = call.receive<OpenAIChatRequest>()
                val reqId = "chatcmpl-${System.currentTimeMillis()}"
                val requestedModel = request.model?.ifBlank { "gemma-4-abliterated" } ?: "gemma-4-abliterated"

                val fullPrompt = buildString {
                    for (msg in request.messages) {
                        val roleLabel = when (msg.role.lowercase()) {
                            "system" -> "System"
                            "assistant" -> "Assistant"
                            else -> "User"
                        }
                        append("$roleLabel: ${msg.content}\n")
                    }
                    append("Assistant:")
                }

                val lastUserMessage = request.messages.lastOrNull { it.role.lowercase() == "user" }?.content ?: ""

                if (request.stream == true) {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        val fullResponseAccumulator = StringBuilder()

                        withContext(Dispatchers.Default) {
                            AgentBridge.processPrompt(fullPrompt) { token ->
                                fullResponseAccumulator.append(token)
                                val chunk = OpenAIChatResponse(
                                    id = reqId,
                                    model = requestedModel,
                                    `object` = "chat.completion.chunk",
                                    choices = listOf(
                                        OpenAIChoice(
                                            index = 0,
                                            delta = OpenAIDelta(content = token),
                                            finishReason = null
                                        )
                                    )
                                )
                                val jsonChunk = Json.encodeToString(OpenAIChatResponse.serializer(), chunk)
                                write("data: $jsonChunk\n\n")
                                flush()
                            }
                        }

                        val doneChunk = OpenAIChatResponse(
                            id = reqId,
                            model = requestedModel,
                            `object` = "chat.completion.chunk",
                            choices = listOf(
                                OpenAIChoice(
                                    index = 0,
                                    delta = OpenAIDelta(),
                                    finishReason = "stop"
                                )
                            )
                        )
                        write("data: ${Json.encodeToString(OpenAIChatResponse.serializer(), doneChunk)}\n\n")
                        write("data: [DONE]\n\n")
                        flush()

                        val clientId = request.user ?: "openai-client"
                        ChatSessionManager.appendMessage(clientId, "User", lastUserMessage)
                        ChatSessionManager.appendMessage(clientId, "Assistant", fullResponseAccumulator.toString().trim())
                    }
                } else {
                    val responseAccumulator = StringBuilder()

                    withContext(Dispatchers.Default) {
                        AgentBridge.processPrompt(fullPrompt) { token ->
                            responseAccumulator.append(token)
                        }
                    }

                    val finalResponseText = responseAccumulator.toString().trim()
                    val clientId = request.user ?: "openai-client"
                    ChatSessionManager.appendMessage(clientId, "User", lastUserMessage)
                    ChatSessionManager.appendMessage(clientId, "Assistant", finalResponseText)

                    call.respond(
                        OpenAIChatResponse(
                            id = reqId,
                            model = requestedModel,
                            choices = listOf(
                                OpenAIChoice(
                                    index = 0,
                                    message = OpenAIMessage(role = "assistant", content = finalResponseText),
                                    finishReason = "stop"
                                )
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to mapOf("message" to (e.localizedMessage ?: "Unknown error"), "type" to "server_error"))
                )
            }
        }

        // --- Termux CLI Dedicated SSE Endpoint ---
        post("/api/agent/stream") {
            try {
                val request = call.receive<ClientMessage>()
                val clientId = request.clientId.ifBlank { "termux-cli" }

                if (request.resetSession) {
                    ChatSessionManager.clearSession(clientId)
                }

                val history = ChatSessionManager.getHistory(clientId)
                val fullPrompt = buildString {
                    for ((role, text) in history) {
                        append("$role: $text\n")
                    }
                    if (!request.cwd.isNullOrBlank()) append("Current Directory: ${request.cwd}\n")
                    if (!request.gitContext.isNullOrBlank()) append("${request.gitContext}\n")
                    if (!request.stdinContent.isNullOrBlank()) append("Piped Input:\n${request.stdinContent}\n")
                    append("User: ${request.prompt}\nAssistant:")
                }

                val responseAccumulator = StringBuilder()

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    withContext(Dispatchers.Default) {
                        AgentBridge.processPrompt(fullPrompt) { token ->
                            responseAccumulator.append(token)
                            write("data: $token\n\n")
                            flush()
                        }
                    }
                    write("data: [DONE]\n\n")
                    flush()
                }

                ChatSessionManager.appendMessage(clientId, "User", request.prompt)
                ChatSessionManager.appendMessage(clientId, "Assistant", responseAccumulator.toString().trim())

            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error"))
                )
            }
        }

        // --- Telegram Bot Webhook Endpoint ---
        post("/api/telegram/webhook") {
            try {
                val update = call.receive<TelegramUpdate>()

                // Handle Inline Button Callback Queries (Approvals)
                val callbackQuery = update.callbackQuery
                if (callbackQuery != null) {
                    val data = callbackQuery.data ?: ""
                    val msg = callbackQuery.message
                    val chatId = msg?.chat?.id
                    val messageId = msg?.messageId

                    if (data.contains(":") && chatId != null && messageId != null) {
                        val parts = data.split(":")
                        val action = parts[0] // "approve" or "deny"
                        val taskId = parts[1]

                        val approved = (action == "approve")
                        val future = TelegramApprovalStore.pendingApprovals.remove(taskId)
                        future?.complete(approved)

                        val statusText = if (approved) "✅ *Action Approved*" else "⛔ *Action Denied*"
                        editTelegramMessage(telegramBotToken, chatId, messageId, "${msg.text}\n\n$statusText")
                    }

                    call.respond(HttpStatusCode.OK)
                    return@post
                }

                // Handle Standard Messages
                val message = update.message
                val text = message?.text
                val chatId = message?.chat?.id

                if (chatId != null && !text.isNullOrBlank()) {
                    val clientId = "telegram-$chatId"
                    if (text.trim().equals("/reset", ignoreCase = true)) {
                        ChatSessionManager.clearSession(clientId)
                        call.respond(HttpStatusCode.OK)
                        return@post
                    }

                    // 1. Send initial ticker message
                    val statusMessageId = sendTelegramMessage(telegramBotToken, chatId, "⚙️ *Agent Initializing...*\n`Processing prompt`")

                    val history = ChatSessionManager.getHistory(clientId)
                    val fullPrompt = buildString {
                        for ((role, msg) in history) {
                            append("$role: $msg\n")
                        }
                        append("User: $text\nAssistant:")
                    }

                    // 2. Execute agent prompt with live ticker updates & approval hooks
                    val responseText = withContext(Dispatchers.Default) {
                        AgentBridge.processPromptWithTelegramUpdates(
                            prompt = fullPrompt,
                            botToken = telegramBotToken,
                            chatId = chatId,
                            messageId = statusMessageId ?: 0L
                        )
                    }

                    // 3. Edit message to final task completion state
                    if (statusMessageId != null) {
                        editTelegramMessage(telegramBotToken, chatId, statusMessageId, "✅ *Task Completed*\n\n$responseText")
                    }

                    ChatSessionManager.appendMessage(clientId, "User", text)
                    ChatSessionManager.appendMessage(clientId, "Assistant", responseText)
                }

                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error"))
                )
            }
        }

        // --- Native Phone App & Web WebSocket Endpoint ---
        webSocket("/ws/agent") {
            activeConnections.add(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val clientMsg = try {
                            Json.decodeFromString(ClientMessage.serializer(), text)
                        } catch (e: Exception) {
                            ClientMessage(clientId = "ws-client", prompt = text)
                        }

                        val clientId = clientMsg.clientId.ifBlank { "ws-client" }
                        if (clientMsg.resetSession) {
                            ChatSessionManager.clearSession(clientId)
                        }

                        try {
                            val history = ChatSessionManager.getHistory(clientId)
                            val fullPrompt = buildString {
                                for ((role, t) in history) {
                                    append("$role: $t\n")
                                }
                                append("User: ${clientMsg.prompt}\nAssistant:")
                            }

                            val responseAccumulator = StringBuilder()

                            withContext(Dispatchers.Default) {
                                AgentBridge.processPrompt(fullPrompt) { token ->
                                    responseAccumulator.append(token)
                                }
                            }

                            val finalResponseText = responseAccumulator.toString().trim()

                            val chunk = ServerResponse(token = finalResponseText, isFinished = false)
                            outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), chunk)))

                            ChatSessionManager.appendMessage(clientId, "User", clientMsg.prompt)
                            ChatSessionManager.appendMessage(clientId, "Assistant", finalResponseText)

                            val finalChunk = ServerResponse(token = "", isFinished = true)
                            outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), finalChunk)))

                        } catch (e: Exception) {
                            val errorChunk = ServerResponse(token = "\nError executing prompt: ${e.localizedMessage}", isFinished = true)
                            val errorJson = Json.encodeToString(ServerResponse.serializer(), errorChunk)
                            outgoing.send(Frame.Text(errorJson))
                        }
                    }
                }
            } catch (e: Exception) {
                // Safe handling of socket disconnects
            } finally {
                activeConnections.remove(this)
            }
        }
    }
}
