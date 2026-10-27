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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
// 3. Multi-Round Chat Session Manager
// ==========================================

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
// 4. Ktor Routing Configuration
// ==========================================

fun Application.configureRouting() {
    val activeConnections = Collections.synchronizedSet(hashSetOf<WebSocketSession>())

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
                        "- WS /ws/agent",
                ContentType.Text.Plain
            )
        }

        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

        // --- OpenAI Standard API Endpoints ---

        // GET /v1/models
        get("/v1/models") {
            call.respond(
                OpenAIModelListResponse(
                    data = listOf(
                        OpenAIModelObject(id = "gemma-4-abliterated"),
                        OpenAIModelObject(id = "gemma-4-e2b")
                    )
                )
            )
        }

        // POST /v1/chat/completions (Supports OpenClaw, Claude Code, & standard OpenAI Clients)
        post("/v1/chat/completions") {
            try {
                val request = call.receive<OpenAIChatRequest>()
                val reqId = "chatcmpl-${System.currentTimeMillis()}"

                // Construct full context prompt from array of messages sent by OpenAI client
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

                // Handle Streaming Requests (SSE formatting)
                if (request.stream == true) {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        val fullResponseAccumulator = StringBuilder()

                        withContext(Dispatchers.Default) {
                            AgentBridge.processPrompt(fullPrompt) { token ->
                                fullResponseAccumulator.append(token)
                                val chunk = OpenAIChatResponse(
                                    id = reqId,
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

                        // Send finish chunk
                        val doneChunk = OpenAIChatResponse(
                            id = reqId,
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

                        // Record session turn
                        val clientId = request.user ?: "openai-client"
                        ChatSessionManager.appendMessage(clientId, "User", lastUserMessage)
                        ChatSessionManager.appendMessage(clientId, "Assistant", fullResponseAccumulator.toString().trim())
                    }
                } else {
                    // Handle Non-Streaming Direct Requests
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
                            
                            // Send token response and completion status using proper suspending calls
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
