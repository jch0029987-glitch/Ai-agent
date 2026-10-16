package com.jeremy.ai.backend

import com.jeremy.ai.agent.AgentBridge
import io.ktor.http.*
import io.ktor.server.application.*
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
        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

        // Unified WebSocket endpoint streaming real-time tokens to Web UI and TV app
        webSocket("/ws/agent") {
            activeConnections.add(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val clientMsg = try {
                            Json.decodeFromString<ClientMessage>(text)
                        } catch (e: Exception) {
                            ClientMessage(clientId = "unknown", deviceType = "unknown", prompt = text)
                        }

                        // Stream real-time tokens back to the Web UI / TV client using AgentBridge
                        try {
                            withContext(Dispatchers.Default) {
                                AgentBridge.processPrompt(clientMsg.prompt) { token ->
                                    val chunk = ServerResponse(token = token, isFinished = false)
                                    // Send individual token chunks non-blocking
                                    kotlinx.coroutines.runBlocking {
                                        outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), chunk)))
                                    }
                                }
                            }

                            // Send completion signal
                            val finalChunk = ServerResponse(token = "", isFinished = true)
                            outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), finalChunk)))

                        } catch (e: Exception) {
                            val errorChunk = ServerResponse(token = "\nError executing prompt: ${e.localizedMessage}", isFinished = true)
                            outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), errorChunk)))
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
