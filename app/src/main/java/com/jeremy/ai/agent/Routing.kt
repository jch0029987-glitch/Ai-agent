package com.jeremy.ai.backend

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
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
    // Keep track of connected clients (Web UI / TV apps)
    val activeConnections = Collections.synchronizedSet(hashSetOf<WebSocketSession>())

    routing {
        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "muse-backend-gateway"))
        }

        // Unified WebSocket endpoint for Web UI and TV app streaming
        webSocket("/ws/agent") {
            activeConnections.add(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val clientMsg = Json.decodeFromString<ClientMessage>(text)

                        // Route request to local agent runner or process via local pipeline
                        val simulatedTokens = listOf("Processing", " query", " from", " ${clientMsg.deviceType}:", " Done!")
                        
                        for ((index, token) in simulatedTokens.withIndex()) {
                            val response = ServerResponse(
                                token = token,
                                isFinished = index == simulatedTokens.size - 1
                            )
                            outgoing.send(Frame.Text(Json.encodeToString(ServerResponse.serializer(), response)))
                            kotlinx.coroutines.delay(80)
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle disconnection or socket errors gracefully
            } finally {
                activeConnections.remove(this)
            }
        }
    }
}
