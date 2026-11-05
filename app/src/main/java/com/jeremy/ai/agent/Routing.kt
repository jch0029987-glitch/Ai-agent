package com.jeremy.ai.backend

import com.jeremy.ai.agent.AgentBridge
import com.jeremy.ai.agent.BuildConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.sse.*

// Official Model Context Protocol (MCP) SDK Ktor server integrations
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import android.os.Environment
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

// ==========================================
// 1. OpenAI Spec Data Models
// ==========================================

@Serializable
data class OpenAIMessage(val role: String, val content: String)

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
data class OpenAIDelta(val role: String? = null, val content: String? = null)

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
data class OpenAIModelListResponse(val `object`: String = "list", val data: List<OpenAIModelObject>)

// ==========================================
// 2. Legacy / Native Client Models
// ==========================================

@Serializable
data class ClientMessage(
    val clientId: String = "default-client",
    val deviceType: String? = "phone",
    val prompt: String = "",
    val cwd: String? = null,
    val gitContext: String? = null,
    val stdinContent: String? = null,
    val resetSession: Boolean = false
)

@Serializable
data class ServerResponse(val token: String, val isFinished: Boolean)

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
data class TelegramMessage(@SerialName("message_id") val messageId: Long, val chat: TelegramChat, val text: String? = null)

@Serializable
data class TelegramChat(val id: Long, val type: String)

@Serializable
data class TelegramCallbackQuery(val id: String, val data: String? = null, val message: TelegramMessage? = null)

// ==========================================
// 4. Stores & Shell Manager
// ==========================================

object TelegramApprovalStore {
    val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
}

object ChatSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    fun getHistory(clientId: String) = sessions.getOrPut(clientId) { mutableListOf() }
    fun clearSession(clientId: String) { sessions.remove(clientId) }
    fun appendMessage(clientId: String, role: String, content: String) { getHistory(clientId).add(Pair(role, content)) }
}

object ShellSessionManager {
    private val workingDirectories = ConcurrentHashMap<Long, File>()

    fun executeCommand(chatId: Long, command: String): String {
        val currentDir = workingDirectories.getOrPut(chatId) { File("/data/data/com.termux/files/home") }
        
        if (command.trim().startsWith("cd ")) {
            val targetDir = command.trim().removePrefix("cd").trim()
            val newDir = if (targetDir.startsWith("/")) File(targetDir) else File(currentDir, targetDir)
            return if (newDir.exists() && newDir.isDirectory) {
                workingDirectories[chatId] = newDir.canonicalFile
                "Directory changed to: ${newDir.canonicalPath}"
            } else {
                "Error: Directory not found: $targetDir"
            }
        }

        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(currentDir)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isBlank()) "Command executed successfully with no output." else output.take(4000)
        } catch (e: Exception) {
            "Shell execution error: ${e.localizedMessage}"
        }
    }
}

@Serializable
data class ScheduledTask(val taskId: String, val chatId: Long, val prompt: String, val executeAtTimestamp: Long)

object CronJobManager {
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    fun addTask(task: ScheduledTask) { scheduledTasks.add(task) }
    fun startDispatcher(botToken: String) {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val now = System.currentTimeMillis()
                val iterator = scheduledTasks.iterator()
                while (iterator.hasNext()) {
                    val task = iterator.next()
                    if (now >= task.executeAtTimestamp) {
                        try {
                            val statusMsgId = sendTelegramMessage(botToken, task.chatId, "⏰ *Cron Reminder Triggered:*\n`${task.prompt}`")
                            val response = AgentBridge.processPromptWithTelegramUpdates(prompt = task.prompt, botToken = botToken, chatId = task.chatId, messageId = statusMsgId ?: 0L)
                            if (statusMsgId != null) {
                                editTelegramMessage(botToken, task.chatId, statusMsgId, "✅ *Cron Task Completed*\n\n$response")
                            }
                        } catch (e: Exception) {}
                        iterator.remove()
                    }
                }
                delay(10000L)
            }
        }
    }
}

// ==========================================
// 5. Utilities & Path Resolution
// ==========================================

fun getAvailableModels(): List<OpenAIModelObject> {
    val modelList = mutableListOf<OpenAIModelObject>()
    try {
        val externalStorage = Environment.getExternalStorageDirectory()
        listOf(File(externalStorage, "Download/models"), File(externalStorage, "Download")).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file -> file.isFile && (file.name.endsWith(".litertlm", true) || file.name.endsWith(".task", true)) }
                    ?.forEach { modelList.add(OpenAIModelObject(id = modelFile.name)) }
            }
        }
    } catch (e: Exception) {}
    if (modelList.isEmpty()) modelList.add(OpenAIModelObject(id = "gemma-4-abliterated"))
    return modelList
}

private fun resolveWorkspaceFile(rawPath: String): File {
    val trimmed = rawPath.trim()
    return when {
        trimmed.startsWith("/") -> File(trimmed)
        trimmed.startsWith("termux:") -> File("/data/data/com.termux/files/home/", trimmed.removePrefix("termux:"))
        else -> File("/data/data/com.termux/files/home/", trimmed)
    }
}

private fun <T> executeTelegramRequest(block: () -> T?): T? {
    var maxRetries = 3
    var delayMs = 1000L
    while (maxRetries > 0) {
        try { return block() } catch (e: Exception) {
            maxRetries--
            if (maxRetries == 0) break
            try { Thread.sleep(delayMs) } catch (_: Exception) {}
            delayMs *= 2
        }
    }
    return null
}

private fun sendTelegramMessage(botToken: String, chatId: Long, text: String): Long? = executeTelegramRequest {
    val url = java.net.URL("https://api.telegram.org/bot$botToken/sendMessage")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    val payload = "{\"chat_id\": $chatId, \"text\": \"${text.replace("\"", "\\\"").replace("\n", "\\n")}\", \"parse_mode\": \"Markdown\"}"
    conn.outputStream.write(payload.toByteArray())
    if (conn.responseCode == 200) {
        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
        "\"message_id\":(\\d+)".toRegex().find(responseStr)?.groupValues?.get(1)?.toLongOrNull()
    } else if (conn.responseCode == 429) throw java.io.IOException("Rate limited") else null
}

private fun editTelegramMessage(botToken: String, chatId: Long, messageId: Long, newText: String): Int? = executeTelegramRequest {
    val url = java.net.URL("https://api.telegram.org/bot$botToken/editMessageText")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    val payload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"${newText.replace("\"", "\\\"").replace("\n", "\\n")}\", \"parse_mode\": \"Markdown\"}"
    conn.outputStream.write(payload.toByteArray())
    if (conn.responseCode == 429) throw java.io.IOException("Rate limited")
    conn.responseCode
}

private fun answerCallbackQuery(botToken: String, callbackQueryId: String): Int? = executeTelegramRequest {
    val url = java.net.URL("https://api.telegram.org/bot$botToken/answerCallbackQuery")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.outputStream.write("{\"callback_query_id\": \"$callbackQueryId\"}".toByteArray())
    if (conn.responseCode == 429) throw java.io.IOException("Rate limited")
    conn.responseCode
}

// ==========================================
// 6. Ktor Routing & Full-Feature MCP Server
// ==========================================

fun Application.configureRouting() {
    install(SSE)

    val activeConnections = Collections.synchronizedSet(hashSetOf<WebSocketSession>())
    val telegramBotToken = BuildConfig.TELEGRAM_BOT_TOKEN

    try { CronJobManager.startDispatcher(telegramBotToken) } catch (e: Exception) {}

    routing {
        // --- Full-Feature Model Context Protocol (MCP) Server Endpoint ---
        mcp("/mcp") {
            serverInfo = Implementation(name = "pixel9-android-mcp", version = "2.0.0")
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                resources = ServerCapabilities.Resources()
            )

            // 1. RESOURCES: Expose system state & workspace info
            resource(
                uri = "workspace://git/status",
                name = "Git Status",
                description = "Uncommitted git changes and branch status"
            ) {
                val output = ShellSessionManager.executeCommand(0L, "git status")
                ReadResourceResult(contents = listOf(io.modelcontextprotocol.kotlin.sdk.ResourceContent(uri = "workspace://git/status", text = output)))
            }

            resource(
                uri = "system://device/info",
                name = "Device Info",
                description = "Pixel 9 hardware and runtime specs"
            ) {
                val info = "Device: Google Pixel 9 (Tensor G4)\nRuntime: Termux / Android Ktor\nAvailable Models: ${getAvailableModels().map { it.id }}"
                ReadResourceResult(contents = listOf(io.modelcontextprotocol.kotlin.sdk.ResourceContent(uri = "system://device/info", text = info)))
            }

            // 2. TOOLS: Full suite of file, shell, and path capabilities
            tool(
                name = "read_workspace_file",
                description = "Read contents of a local file on Termux or Android storage"
            ) { args: Map<String, Any?> ->
                val rawPath = args["path"]?.toString() ?: ""
                val targetFile = resolveWorkspaceFile(rawPath)
                val content = if (targetFile.exists() && targetFile.canRead()) targetFile.readText().take(5000) else "Error: File not found"
                CallToolResult(content = listOf(TextContent(text = content)))
            }

            tool(
                name = "write_workspace_file",
                description = "Write or overwrite a file in the workspace"
            ) { args: Map<String, Any?> ->
                val rawPath = args["path"]?.toString() ?: ""
                val content = args["content"]?.toString() ?: ""
                val targetFile = resolveWorkspaceFile(rawPath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(content)
                CallToolResult(content = listOf(TextContent(text = "Successfully wrote ${targetFile.absolutePath}")))
            }

            tool(
                name = "list_directory",
                description = "List files and directories in a given path"
            ) { args: Map<String, Any?> ->
                val rawPath = args["path"]?.toString() ?: "."
                val dir = resolveWorkspaceFile(rawPath)
                val listing = if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.joinToString("\n") { if (it.isDirectory) "[DIR]  ${it.name}" else "[FILE] ${it.name}" } ?: "Empty directory"
                } else "Error: Invalid directory"
                CallToolResult(content = listOf(TextContent(text = listing)))
            }

            tool(
                name = "execute_shell",
                description = "Execute a shell command inside the Termux environment context"
            ) { args: Map<String, Any?> ->
                val command = args["command"]?.toString() ?: ""
                val output = ShellSessionManager.executeCommand(0L, command)
                CallToolResult(content = listOf(TextContent(text = output)))
            }
        }

        // --- Standard Landing & OpenAI Endpoints ---
        get("/") {
            call.respondText("Ai-agent Local OpenAI Server & Full MCP Gateway Online", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "agent-backend-gateway"))
        }

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
                        val roleLabel = when (msg.role.lowercase()) { "system" -> "System"; "assistant" -> "Assistant"; else -> "User" }
                        append("$roleLabel: ${msg.content}\n")
                    }
                    append("Assistant:")
                }

                val lastUserMessage = request.messages.lastOrNull { it.role.lowercase() == "user" }?.content ?: ""

                if (request.stream == true) {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        val fullAcc = StringBuilder()
                        withContext(Dispatchers.Default) {
                            AgentBridge.processPrompt(fullPrompt) { token ->
                                fullAcc.append(token)
                                val chunk = OpenAIChatResponse(id = reqId, model = requestedModel, `object` = "chat.completion.chunk", choices = listOf(OpenAIChoice(index = 0, delta = OpenAIDelta(content = token))))
                                write("data: ${Json.encodeToString(OpenAIChatResponse.serializer(), chunk)}\n\n")
                                flush()
                            }
                        }
                        val doneChunk = OpenAIChatResponse(id = reqId, model = requestedModel, `object` = "chat.completion.chunk", choices = listOf(OpenAIChoice(index = 0, delta = OpenAIDelta(), finishReason = "stop")))
                        write("data: ${Json.encodeToString(OpenAIChatResponse.serializer(), doneChunk)}\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                        val clientId = request.user ?: "openai-client"
                        ChatSessionManager.appendMessage(clientId, "User", lastUserMessage)
                        ChatSessionManager.appendMessage(clientId, "Assistant", fullAcc.toString().trim())
                    }
                } else {
                    val responseAcc = StringBuilder()
                    withContext(Dispatchers.Default) { AgentBridge.processPrompt(fullPrompt) { token -> responseAcc.append(token) } }
                    val finalResponseText = responseAcc.toString().trim()
                    val clientId = request.user ?: "openai-client"
                    ChatSessionManager.appendMessage(clientId, "User", lastUserMessage)
                    ChatSessionManager.appendMessage(clientId, "Assistant", finalResponseText)
                    call.respond(OpenAIChatResponse(id = reqId, model = requestedModel, choices = listOf(OpenAIChoice(index = 0, message = OpenAIMessage(role = "assistant", content = finalResponseText), finishReason = "stop"))))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to mapOf("message" to (e.localizedMessage ?: "Unknown error"))))
            }
        }

        // --- Telegram Webhook Endpoint ---
        post("/api/telegram/webhook") {
            try {
                val update = call.receive<TelegramUpdate>()
                val callbackQuery = update.callbackQuery
                if (callbackQuery != null) {
                    answerCallbackQuery(telegramBotToken, callbackQuery.id)
                    val data = callbackQuery.data ?: ""
                    val msg = callbackQuery.message
                    val chatId = msg?.chat?.id
                    val messageId = msg?.messageId
                    if (data.contains(":") && chatId != null && messageId != null) {
                        val parts = data.split(":")
                        val approved = (parts[0] == "approve")
                        TelegramApprovalStore.pendingApprovals.remove(parts[1])?.complete(approved)
                        editTelegramMessage(telegramBotToken, chatId, messageId, "${msg.text}\n\n${if (approved) "✅ *Action Approved*" else "⛔ *Action Denied*"}")
                    }
                    call.respond(HttpStatusCode.OK)
                    return@post
                }

                val message = update.message
                val text = message?.text
                val chatId = message?.chat?.id

                if (chatId != null && !text.isNullOrBlank()) {
                    val clientId = "telegram-$chatId"
                    if (text.trim().equals("/reset", ignoreCase = true)) {
                        ChatSessionManager.clearSession(clientId)
                        sendTelegramMessage(telegramBotToken, chatId, "🔄 Session history cleared.")
                        call.respond(HttpStatusCode.OK)
                        return@post
                    }

                    if (text.startsWith("/shell ")) {
                        val cmd = text.removePrefix("/shell").trim()
                        val statusId = sendTelegramMessage(telegramBotToken, chatId, "💻 *Executing Shell:*\n`$cmd`")
                        val result = ShellSessionManager.executeCommand(chatId, cmd)
                        if (statusId != null) editTelegramMessage(telegramBotToken, chatId, statusId, "💻 *Shell Output:*\n```bash\n$result\n```")
                        call.respond(HttpStatusCode.OK)
                        return@post
                    }

                    val statusMessageId = sendTelegramMessage(telegramBotToken, chatId, "⚙️ *Agent Initializing...*\n`Processing prompt`")
                    val history = ChatSessionManager.getHistory(clientId)
                    val fullPrompt = buildString {
                        for ((role, msg) in history) append("$role: $msg\n")
                        append("User: $text\nAssistant:")
                    }

                    val responseText = withContext(Dispatchers.Default) {
                        AgentBridge.processPromptWithTelegramUpdates(prompt = fullPrompt, botToken = telegramBotToken, chatId = chatId, messageId = statusMessageId ?: 0L)
                    }

                    if (statusMessageId != null) editTelegramMessage(telegramBotToken, chatId, statusMessageId, "✅ *Task Completed*\n\n$responseText")
                    ChatSessionManager.appendMessage(clientId, "User", text)
                    ChatSessionManager.appendMessage(clientId, "Assistant", responseText)
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "message" to (e.localizedMessage ?: "Unknown error")))
            }
        }
    }
}
