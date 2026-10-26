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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections

@Serializable
data class ClientMessage(
    val clientId: String,
    val deviceType: String, // "web", "tv", or "termux-cli"
    val prompt: String,
    val cwd: String? = null,
    val gitContext: String? = null,
    val stdinContent: String? = null
)

@Serializable
data class ServerResponse(
    val token: String,
    val isFinished: Boolean
)

fun Application.configureRouting() {
    val activeConnections = Collections.synchronizedSet(hashSetOf<WebSocketSession>())

    routing {
        // 1. Root landing page to avoid 404 in the browser
        get("/") {
            call.respondText(
                "Ai-agent Ktor Server is Online\nEndpoints:\n- GET /health\n- POST /api/agent/prompt\n- POST /api/agent/stream (SSE)\n- WS /ws/agent",
                ContentType.Text.Plain
            )
        }

        // 2. Health check endpoint
        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

        // 3. REST prompt endpoint for standard requests
        post("/api/agent/prompt") {
            try {
                val request = call.receive<ClientMessage>()
                val fullResponse = StringBuilder()

                // Augment prompt with Termux context if available
                val contextualizedPrompt = buildString {
                    if (!request.cwd.isNullOrBlank()) append("Current Directory: ${request.cwd}\n")
                    if (!request.gitContext.isNullOrBlank()) append("${request.gitContext}\n")
                    if (!request.stdinContent.isNullOrBlank()) append("Piped Input:\n${request.stdinContent}\n")
                    append("Prompt: ${request.prompt}")
                }

                withContext(Dispatchers.Default) {
                    AgentBridge.processPrompt(contextualizedPrompt) { token ->
                        fullResponse.append(token)
                    }
                }

                call.respond(
                    mapOf(
                        "status" to "success",
                        "prompt" to request.prompt,
                        "response" to fullResponse.toString()
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "status" to "error",
                        "message" to (e.localizedMessage ?: "Unknown error")
                    )
                )
            }
        }

        // 4. Server-Sent Events (SSE) streaming endpoint for the Termux CLI
        post("/api/agent/stream") {
            try {
                val request = call.receive<ClientMessage>()

                val contextualizedPrompt = buildString {
                    if (!request.cwd.isNullOrBlank()) append("Current Directory: ${request.cwd}\n")
                    if (!request.gitContext.isNullOrBlank()) append("${request.gitContext}\n")
                    if (!request.stdinContent.isNullOrBlank()) append("Piped Input:\n${request.stdinContent}\n")
                    append("Prompt: ${request.prompt}")
                }

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    withContext(Dispatchers.Default) {
                        AgentBridge.processPrompt(contextualizedPrompt) { token ->
                            // Format as standard Server-Sent Event data block
                            write("data: $token\n\n")
                            flush()
                        }
                    }
                    // Send termination signal chunk
                    write("data: [DONE]\n\n")
                    flush()
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error"))
                )
            }
        }

        // 5. Unified WebSocket endpoint streaming real-time tokens to Web UI and TV app
        webSocket("/ws/agent") {
            activeConnections.add(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val clientMsg = try {
                            Json.decodeFromString(ClientMessage.serializer(), text)
                        } catch (e: Exception) {
                            ClientMessage(clientId = "unknown", deviceType = "unknown", prompt = text)
                        }

                        try {
                            withContext(Dispatchers.Default) {
                                AgentBridge.processPrompt(clientMsg.prompt) { token ->
                                    val chunk = ServerResponse(token = token, isFinished = false)
                                    val jsonString = Json.encodeToString(ServerResponse.serializer(), chunk)
                                    kotlinx.coroutines.runBlocking {
                                        outgoing.send(Frame.Text(jsonString))
                                    }
                                }
                            }

                            val finalChunk = ServerResponse(token = "", isFinished = true)
                            val finalJson = Json.encodeToString(ServerResponse.serializer(), finalChunk)
                            outgoing.send(Frame.Text(finalJson))

                        } catch (e: Exception) {
                            val errorChunk = ServerResponse(token = "\nError executing prompt: ${e.localizedMessage}", isFinished = true)
                            val errorJson = Json.encodeToString(ServerResponse.serializer(), errorChunk)
                            outgoing.send(Frame.Text(errorJson))
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle socket disconnects safely
            } finally {
                activeConnections.remove(this)
            }
        }
    }
}
