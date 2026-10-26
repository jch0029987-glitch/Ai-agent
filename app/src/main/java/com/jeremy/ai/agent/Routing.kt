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
    val deviceType: String, // "web" or "tv"
    val prompt: String
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
                "Ai-agent Ktor Server is Online\nEndpoints:\n- GET /health\n- POST /api/agent/prompt\n- WS /ws/agent",
                ContentType.Text.Plain
            )
        }

        // 2. Health check endpoint
        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

        // 3. REST prompt endpoint for Termux/CLI integration
        post("/api/agent/prompt") {
            try {
                val request = call.receive<ClientMessage>()
                val fullResponse = StringBuilder()

                withContext(Dispatchers.Default) {
                    AgentBridge.processPrompt(request.prompt) { token ->
                        fullResponse.append(token)
                    }
                }

                call.respond(mapOf(
                    "status" to "success",
                    "prompt" to request.prompt,
                    "response" to fullResponse.toString()
                ))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("status" to "error", "message" to e.localizedMessage ?: "Unknown error")
                )
            }
        }

        // 4. Unified WebSocket endpoint streaming real-time tokens to Web UI and TV app
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

                        // Stream real-time tokens back to the Web UI / TV client using AgentBridge
                        try {
                            withContext(Dispatchers.Default) {
                                AgentBridge.processPrompt(clientMsg.prompt) { token ->
                                    val chunk = ServerResponse(token = token, isFinished = false)
                                    val jsonString = Json.encodeToString(ServerResponse.serializer(), chunk)
                                    // Send individual token chunks non-blocking
                                    kotlinx.coroutines.runBlocking {
                                        outgoing.send(Frame.Text(jsonString))
                                    }
                                }
                            }

                            // Send completion signal
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
