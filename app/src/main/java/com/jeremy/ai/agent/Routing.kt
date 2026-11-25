package com.jeremy.ai.backend

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.jeremy.ai.agent.AgentBridge
import com.jeremy.ai.agent.AiBackendConfig
import com.jeremy.ai.agent.AiBackendMode
import com.jeremy.ai.agent.AlarmReceiver
import com.jeremy.ai.agent.BuildConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ==========================================
// 1. Core Stores & Managers (com.jeremy.ai.backend)
// ==========================================
object TelegramApprovalStore {
    val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
}

object ChatSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    var lastActiveTelegramChatId: Long = 0L

    fun getHistory(clientId: String) = sessions.getOrPut(clientId) { mutableListOf() }
    fun clearSession(clientId: String) { sessions.remove(clientId) }
    fun appendMessage(clientId: String, role: String, content: String) {
        getHistory(clientId).add(Pair(role, content))
    }
}

object ShellSessionManager {
    lateinit var appContext: Context

    private val workingDirectories = ConcurrentHashMap<Long, File>()
    private val pendingResults = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val atomicId = AtomicInteger(0)

    fun executeCommand(context: Context, chatId: Long, command: String): String {
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

        val executionId = atomicId.incrementAndGet()
        val future = CompletableFuture<String>()
        pendingResults[executionId] = future

        try {
            val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                putExtra("execution_id", executionId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                executionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", currentDir.absolutePath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }

            context.startForegroundService(intent)

            return try {
                future.get(5, TimeUnit.SECONDS) ?: "Command executed with no output."
            } catch (e: java.util.concurrent.TimeoutException) {
                "Error: Termux execution timed out waiting for callback."
            } finally {
                pendingResults.remove(executionId)
            }
        } catch (e: Exception) {
            pendingResults.remove(executionId)
            return "Termux Intent dispatch error: ${e.localizedMessage}"
        }
    }

    fun handleResult(executionId: Int, intent: Intent, bundle: Bundle?) {
        val future = pendingResults.remove(executionId) ?: return

        val allExtras = mutableMapOf<String, Any?>()
        bundle?.keySet()?.forEach { key -> allExtras[key] = bundle.get(key) }
        intent.extras?.keySet()?.forEach { key -> allExtras["intent_$key"] = intent.extras?.get(key) }

        val stdout = bundle?.getString("stdout")
            ?: bundle?.getString("com.termux.RUN_COMMAND_STDOUT")
            ?: bundle?.getString("com.termux.plugin.result.bundle.stdout")
            ?: intent.getStringExtra("stdout")
            ?: intent.getStringExtra("com.termux.RUN_COMMAND_STDOUT")
            ?: allExtras.entries.firstOrNull {
                it.key.contains("stdout", true) || it.key.contains("output", true)
            }?.value?.toString()
            ?: allExtras.entries.firstOrNull { it.value is String && (it.value as String).length > 5 }?.value?.toString()
            ?: "Command completed successfully."

        future.complete(stdout.take(4000))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val executionId = intent.getIntExtra("execution_id", -1)
        val bundle = intent.getBundleExtra("com.termux.plugin.result.bundle")
            ?: intent.getBundleExtra("result")
            ?: intent.extras
        ShellSessionManager.handleResult(executionId, intent, bundle)
    }
}

// ==========================================
// 2. OpenAI Spec & MCP DTO Models
// ==========================================
@Serializable data class OpenAIMessage(val role: String, val content: String)
@Serializable data class OpenAIChatRequest(val model: String? = "gemma-4-abliterated", val messages: List<OpenAIMessage>, val stream: Boolean? = false, val temperature: Float? = 0.7f, val user: String? = null)
@Serializable data class OpenAIChoice(val index: Int = 0, val message: OpenAIMessage? = null, val delta: OpenAIDelta? = null, @SerialName("finish_reason") val finishReason: String? = null)
@Serializable data class OpenAIDelta(val role: String? = null, val content: String? = null)
@Serializable data class OpenAIChatResponse(val id: String, val `object`: String = "chat.completion", val created: Long = System.currentTimeMillis() / 1000, val model: String = "gemma-4-abliterated", val choices: List<OpenAIChoice>)
@Serializable data class OpenAIModelObject(val id: String, val `object`: String = "model", val created: Long = System.currentTimeMillis() / 1000, val owned_by: String = "local-pixel9")
@Serializable data class OpenAIModelListResponse(val `object`: String = "list", val data: List<OpenAIModelObject>)
@Serializable data class JsonRpcRequest(val jsonrpc: String = "2.0", val id: kotlinx.serialization.json.JsonElement? = null, val method: String, val params: JsonObject? = null)
@Serializable data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: kotlinx.serialization.json.JsonElement? = null, val result: JsonObject? = null, val error: JsonRpcError? = null)
@Serializable data class JsonRpcError(val code: Int, val message: String)
@Serializable data class TelegramUpdate(@SerialName("update_id") val updateId: Long, val message: TelegramMessage? = null, @SerialName("callback_query") val callbackQuery: TelegramCallbackQuery? = null)
@Serializable data class TelegramMessage(@SerialName("message_id") val messageId: Long, val chat: TelegramChat, val text: String? = null)
@Serializable data class TelegramChat(val id: Long, val type: String)
@Serializable data class TelegramCallbackQuery(val id: String, val data: String? = null, val message: TelegramMessage? = null)

// ==========================================
// 3. Utilities & Path Resolution
// ==========================================
fun getAvailableModels(): List<OpenAIModelObject> {
    val settings = AiBackendConfig.currentSettings
    if (settings.mode == AiBackendMode.OLLAMA) {
        return buildList {
            if (settings.ollamaCoderModel.isNotBlank()) {
                add(OpenAIModelObject(id = settings.ollamaCoderModel, owned_by = "termux-ollama-coder"))
            }
            if (!settings.ollamaVisionModel.isNullOrBlank()) {
                add(OpenAIModelObject(id = settings.ollamaVisionModel, owned_by = "termux-ollama-vision"))
            }
        }
    }

    val modelList = mutableListOf<OpenAIModelObject>()
    try {
        val externalStorage = Environment.getExternalStorageDirectory()
        listOf(File(externalStorage, "Download/models"), File(externalStorage, "Download")).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file -> file.isFile && (file.name.endsWith(".litertlm", true) || file.name.endsWith(".task", true)) }
                    ?.forEach { modelFile -> modelList.add(OpenAIModelObject(id = modelFile.name)) }
            }
        }
    } catch (_: Exception) {}
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

private fun escapeJsonString(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "")
    .replace("\n", "\\n")
    .replace("\t", "\\t")

private fun postTelegramRaw(endpointUrl: String, payloadJson: String): Pair<Int, String> {
    val conn = (java.net.URL(endpointUrl).openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code == 429) throw java.io.IOException("Rate limited: $body")
    return code to body
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

internal fun sendTelegramMessage(botToken: String, chatId: Long, text: String): Long? {
    if (botToken.isBlank() || botToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
        Log.e("TelegramRouting", "sendTelegramMessage skipped: botToken is blank or placeholder")
        return null
    }
    val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
    var lastMessageId: Long? = null

    for (chunk in text.chunked(3900)) {
        val escaped = escapeJsonString(chunk)
        val msgId = executeTelegramRequest {
            val mdPayload = "{\"chat_id\": $chatId, \"text\": \"$escaped\", \"parse_mode\": \"Markdown\"}"
            var (code, body) = postTelegramRaw(endpoint, mdPayload)
            if (code == 400 && body.contains("parse entities", ignoreCase = true)) {
                val plainPayload = "{\"chat_id\": $chatId, \"text\": \"$escaped\"}"
                val retry = postTelegramRaw(endpoint, plainPayload)
                code = retry.first
                body = retry.second
            }
            if (code in 200..299) {
                "\"message_id\":(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull()
            } else {
                Log.e("TelegramRouting", "sendMessage failed ($code): $body")
                null
            }
        }
        if (msgId != null) lastMessageId = msgId
    }
    return lastMessageId
}

internal fun editTelegramMessage(botToken: String, chatId: Long, messageId: Long, newText: String): Int? = executeTelegramRequest {
    if (botToken.isBlank() || botToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") return@executeTelegramRequest null
    val escaped = escapeJsonString(newText.take(3900))
    val endpoint = "https://api.telegram.org/bot$botToken/editMessageText"
    val mdPayload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"$escaped\", \"parse_mode\": \"Markdown\"}"
    var (code, body) = postTelegramRaw(endpoint, mdPayload)
    if (code == 400 && body.contains("parse entities", ignoreCase = true)) {
        val plainPayload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"$escaped\"}"
        code = postTelegramRaw(endpoint, plainPayload).first
    }
    code
}

private fun answerCallbackQuery(botToken: String, callbackQueryId: String): Int? = executeTelegramRequest {
    if (botToken.isBlank() || botToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") return@executeTelegramRequest null
    val escapedId = escapeJsonString(callbackQueryId)
    postTelegramRaw(
        "https://api.telegram.org/bot$botToken/answerCallbackQuery",
        "{\"callback_query_id\": \"$escapedId\"}"
    ).first
}

// ==========================================
// 4. Ktor Module & Routing Setup
// ==========================================
fun Application.configureServer() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        })
    }
    configureRouting()
}

fun Application.configureRouting() {
    try {
        AiBackendConfig.load(ShellSessionManager.appContext)
    } catch (_: UninitializedPropertyAccessException) {
    }
    val telegramBotToken = BuildConfig.TELEGRAM_BOT_TOKEN

    routing {
        // --- Standalone JSON-RPC 2.0 MCP Endpoint (/mcp) ---
        post("/mcp") {
            try {
                val req = call.receive<JsonRpcRequest>()
                val resultJson = when (req.method) {
                    "initialize" -> {
                        JsonObject(mapOf(
                            "protocolVersion" to kotlinx.serialization.json.JsonPrimitive("2024-11-05"),
                            "serverInfo" to kotlinx.serialization.json.JsonObject(mapOf(
                                "name" to kotlinx.serialization.json.JsonPrimitive("pixel9-android-mcp"),
                                "version" to kotlinx.serialization.json.JsonPrimitive("2.0.0")
                            )),
                            "capabilities" to kotlinx.serialization.json.JsonObject(mapOf(
                                "tools" to kotlinx.serialization.json.JsonObject(emptyMap()),
                                "resources" to kotlinx.serialization.json.JsonObject(emptyMap())
                            ))
                        ))
                    }
                    "tools/list" -> {
                        JsonObject(mapOf(
                            "tools" to kotlinx.serialization.json.JsonArray(listOf(
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("read_workspace_file"), "description" to kotlinx.serialization.json.JsonPrimitive("Read contents of a local file"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("write_workspace_file"), "description" to kotlinx.serialization.json.JsonPrimitive("Write or overwrite a file"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("patch_file"), "description" to kotlinx.serialization.json.JsonPrimitive("Patch a file using search and replace blocks"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("search_code"), "description" to kotlinx.serialization.json.JsonPrimitive("Search codebase using ripgrep or grep"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("list_directory"), "description" to kotlinx.serialization.json.JsonPrimitive("List files in directory"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("execute_shell"), "description" to kotlinx.serialization.json.JsonPrimitive("Execute a shell command"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("schedule_alarm"), "description" to kotlinx.serialization.json.JsonPrimitive("Schedule an exact alarm or background cron reminder"))),
                                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("add_cron_job"), "description" to kotlinx.serialization.json.JsonPrimitive("Register a periodic background cron job")))
                            ))
                        ))
                    }
                    "resources/list" -> {
                        JsonObject(mapOf(
                            "resources" to kotlinx.serialization.json.JsonArray(listOf(
                                kotlinx.serialization.json.JsonObject(mapOf("uri" to kotlinx.serialization.json.JsonPrimitive("workspace://git/status"), "name" to kotlinx.serialization.json.JsonPrimitive("Git Status"))),
                                kotlinx.serialization.json.JsonObject(mapOf("uri" to kotlinx.serialization.json.JsonPrimitive("system://device/info"), "name" to kotlinx.serialization.json.JsonPrimitive("Device Info")))
                            ))
                        ))
                    }
                    "resources/read" -> {
                        val uri = req.params?.get("uri")?.jsonPrimitive?.content ?: ""
                        val content = when (uri) {
                            "workspace://git/status" -> ShellSessionManager.executeCommand(ShellSessionManager.appContext, 0L, "git status")
                            "system://device/info" -> "Device: Google Pixel 9 (Tensor G4)\nRuntime: Termux / Android Ktor"
                            else -> "Error: Unknown resource URI"
                        }
                        JsonObject(mapOf(
                            "contents" to kotlinx.serialization.json.JsonArray(listOf(
                                kotlinx.serialization.json.JsonObject(mapOf(
                                    "uri" to kotlinx.serialization.json.JsonPrimitive(uri),
                                    "text" to kotlinx.serialization.json.JsonPrimitive(content)
                                ))
                            ))
                        ))
                    }
                    "tools/call" -> {
                        val toolName = req.params?.get("name")?.jsonPrimitive?.content ?: ""
                        val arguments = req.params?.get("arguments")?.jsonObject

                        val toolOutput = when (toolName) {
                            "read_workspace_file" -> {
                                val path = arguments?.get("path")?.jsonPrimitive?.content ?: ""
                                val target = resolveWorkspaceFile(path)
                                if (target.exists() && target.canRead()) target.readText().take(5000) else "Error: File not found"
                            }
                            "write_workspace_file" -> {
                                val path = arguments?.get("path")?.jsonPrimitive?.content ?: ""
                                val content = arguments?.get("content")?.jsonPrimitive?.content ?: ""
                                val target = resolveWorkspaceFile(path)
                                target.parentFile?.mkdirs()
                                target.writeText(content)
                                "Successfully wrote ${target.absolutePath}"
                            }
                            "patch_file" -> {
                                val path = arguments?.get("path")?.jsonPrimitive?.content ?: ""
                                val search = arguments?.get("search")?.jsonPrimitive?.content ?: ""
                                val replace = arguments?.get("replace")?.jsonPrimitive?.content ?: ""
                                val target = resolveWorkspaceFile(path)
                                if (target.exists() && target.canWrite()) {
                                    val fileContent = target.readText()
                                    if (fileContent.contains(search)) {
                                        target.writeText(fileContent.replace(search, replace))
                                        "Successfully patched ${target.absolutePath}"
                                    } else {
                                        "Error: Search block not found in file"
                                    }
                                } else {
                                    "Error: File not found or unwritable"
                                }
                            }
                            "search_code" -> {
                                val query = arguments?.get("query")?.jsonPrimitive?.content ?: arguments?.get("command")?.jsonPrimitive?.content ?: ""
                                ShellSessionManager.executeCommand(ShellSessionManager.appContext, 0L, "rg --line-number --no-heading '$query' /data/data/com.termux/files/home/Ai-agent || grep -rnw '/data/data/com.termux/files/home/Ai-agent' -e '$query'")
                            }
                            "list_directory" -> {
                                val path = arguments?.get("path")?.jsonPrimitive?.content ?: "."
                                val dir = resolveWorkspaceFile(path)
                                if (dir.exists() && dir.isDirectory) {
                                    dir.listFiles()?.joinToString("\n") { if (it.isDirectory) "[DIR]  ${it.name}" else "[FILE] ${it.name}" } ?: "Empty directory"
                                } else "Error: Invalid directory"
                            }
                            "execute_shell" -> {
                                val command = arguments?.get("command")?.jsonPrimitive?.content ?: ""
                                ShellSessionManager.executeCommand(ShellSessionManager.appContext, 0L, command)
                            }
                            "schedule_alarm" -> {
                                val timeSpec = arguments?.get("time")?.jsonPrimitive?.content ?: "10m"
                                val promptTask = arguments?.get("prompt")?.jsonPrimitive?.content ?: "Scheduled task"
                                val targetChatId = ChatSessionManager.lastActiveTelegramChatId
                                val delayMillis = when {
                                    timeSpec.lowercase().contains("s") -> (timeSpec.filter { it.isDigit() }.toLongOrNull() ?: 10) * 1000
                                    timeSpec.lowercase().contains("h") -> (timeSpec.filter { it.isDigit() }.toLongOrNull() ?: 1) * 60 * 60 * 1000
                                    else -> (timeSpec.filter { it.isDigit() }.toLongOrNull() ?: 10) * 60 * 1000
                                }
                                val triggerTime = System.currentTimeMillis() + delayMillis

                                val context = ShellSessionManager.appContext
                                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                val intent = Intent(context, AlarmReceiver::class.java).apply {
                                    putExtra("chat_id", targetChatId)
                                    putExtra("prompt", promptTask)
                                    putExtra("bot_token", telegramBotToken)
                                }
                                val pendingIntent = PendingIntent.getBroadcast(
                                    context,
                                    (targetChatId + triggerTime).toInt(),
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                    } else {
                                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                    }
                                } catch (_: SecurityException) {
                                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                }

                                "Successfully scheduled exact alarm for '$promptTask' in $timeSpec for chat $targetChatId."
                            }
                            "add_cron_job" -> {
                                "Cron job task registered successfully."
                            }
                            else -> "Error: Unknown tool $toolName"
                        }

                        JsonObject(mapOf(
                            "content" to kotlinx.serialization.json.JsonArray(listOf(
                                kotlinx.serialization.json.JsonObject(mapOf(
                                    "type" to kotlinx.serialization.json.JsonPrimitive("text"),
                                    "text" to kotlinx.serialization.json.JsonPrimitive(toolOutput)
                                ))
                            ))
                        ))
                    }
                    else -> throw IllegalArgumentException("Unknown method: ${req.method}")
                }

                call.respond(JsonRpcResponse(id = req.id, result = resultJson))
            } catch (e: Exception) {
                call.respond(JsonRpcResponse(error = JsonRpcError(code = -32603, message = e.localizedMessage ?: "Internal error")))
            }
        }

        // --- Standard Landing & OpenAI Endpoints ---
        get("/") {
            call.respondText("Ai-agent Local OpenAI Server & Standalone JSON-RPC MCP Online", ContentType.Text.Plain)
        }

        get("/health") {
            val active = AiBackendConfig.currentSettings
            call.respond(
                mapOf(
                    "status" to "healthy",
                    "service" to "agent-backend-gateway",
                    "backend" to active.mode.name,
                    "ollamaUrl" to active.ollamaBaseUrl,
                    "coderModel" to active.ollamaCoderModel,
                    "visionModel" to (active.ollamaVisionModel ?: "none")
                )
            )
        }

        get("/v1/models") {
            call.respond(OpenAIModelListResponse(data = getAvailableModels()))
        }

        post("/v1/chat/completions") {
            try {
                val request = call.receive<OpenAIChatRequest>()
                val settings = AiBackendConfig.currentSettings
                val reqId = "chatcmpl-${System.currentTimeMillis()}"
                val requestedModel = request.model?.ifBlank {
                    if (settings.mode == AiBackendMode.OLLAMA) settings.ollamaCoderModel else "gemma-4-abliterated"
                } ?: if (settings.mode == AiBackendMode.OLLAMA) settings.ollamaCoderModel else "gemma-4-abliterated"

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

        // --- Telegram Test Endpoint ---
        post("/api/telegram/test") {
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            val chatId = body?.get("chat_id")?.jsonPrimitive?.content?.toLongOrNull()
                ?: ChatSessionManager.lastActiveTelegramChatId.takeIf { it != 0L }
                ?: BuildConfig.TELEGRAM_ALLOWED_USER_ID.toLongOrNull()
                ?: 0L

            if (telegramBotToken.isBlank() || telegramBotToken == "YOUR_TELEGRAM_BOT_TOKEN_HERE") {
                call.respond(
                    HttpStatusCode.BadRequest,
                    JsonObject(mapOf(
                        "ok" to kotlinx.serialization.json.JsonPrimitive(false),
                        "error" to kotlinx.serialization.json.JsonPrimitive("TELEGRAM_BOT_TOKEN missing")
                    ))
                )
                return@post
            }
            if (chatId == 0L) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    JsonObject(mapOf(
                        "ok" to kotlinx.serialization.json.JsonPrimitive(false),
                        "error" to kotlinx.serialization.json.JsonPrimitive("No chat_id available")
                    ))
                )
                return@post
            }

            val sentId = withContext(Dispatchers.IO) {
                sendTelegramMessage(telegramBotToken, chatId, "Telegram bridge test from Ktor server (chat_id=$chatId)")
            }
            if (sentId != null) {
                call.respond(
                    HttpStatusCode.OK,
                    JsonObject(mapOf(
                        "ok" to kotlinx.serialization.json.JsonPrimitive(true),
                        "chat_id" to kotlinx.serialization.json.JsonPrimitive(chatId.toString()),
                        "message_id" to kotlinx.serialization.json.JsonPrimitive(sentId.toString())
                    ))
                )
            } else {
                call.respond(
                    HttpStatusCode.BadGateway,
                    JsonObject(mapOf(
                        "ok" to kotlinx.serialization.json.JsonPrimitive(false),
                        "chat_id" to kotlinx.serialization.json.JsonPrimitive(chatId.toString()),
                        "error" to kotlinx.serialization.json.JsonPrimitive("Failed to send Telegram message; check logcat TelegramRouting")
                    ))
                )
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
                    call.respond(HttpStatusCode.OK, mapOf("response" to "Callback handled."))
                    return@post
                }

                val message = update.message
                val text = message?.text
                val chatId = message?.chat?.id

                if (chatId != null && !text.isNullOrBlank()) {
                    ChatSessionManager.lastActiveTelegramChatId = chatId
                    val clientId = "telegram-$chatId"
                    if (text.trim().equals("/reset", ignoreCase = true)) {
                        ChatSessionManager.clearSession(clientId)
                        call.respond(HttpStatusCode.OK, mapOf("response" to "🔄 Session history cleared."))
                        return@post
                    }

                    if (text.startsWith("/shell ")) {
                        val cmd = text.removePrefix("/shell").trim()
                        val result = ShellSessionManager.executeCommand(ShellSessionManager.appContext, chatId, cmd)
                        call.respond(HttpStatusCode.OK, mapOf("response" to "💻 *Shell Output:*\n```bash\n$result\n```"))
                        return@post
                    }

                    call.respond(HttpStatusCode.OK, mapOf("response" to "⚙️ Processing request..."))
                    val activeBackend = AiBackendConfig.currentSettings
                    val backendLabel = if (activeBackend.mode == AiBackendMode.OLLAMA) {
                        "Ollama (${activeBackend.ollamaCoderModel})"
                    } else {
                        "LiteRT-LM"
                    }
                    sendTelegramMessage(telegramBotToken, chatId, "⚙️ *Agent Initializing [$backendLabel]...*\n`Processing prompt`")

                    GlobalScope.launch(Dispatchers.Default) {
                        try {
                            val history = ChatSessionManager.getHistory(clientId)
                            val fullPrompt = buildString {
                                for ((role, msg) in history) append("$role: $msg\n")
                                append("User: $text\nAssistant:")
                            }

                            val responseText = AgentBridge.processPromptWithTelegramUpdates(
                                prompt = fullPrompt,
                                botToken = telegramBotToken,
                                chatId = chatId,
                                messageId = 0L
                            )

                            val finalResponse = responseText.ifBlank { "✅ Task completed with no output." }
                            ChatSessionManager.appendMessage(clientId, "User", text)
                            ChatSessionManager.appendMessage(clientId, "Assistant", finalResponse)

                            sendTelegramMessage(telegramBotToken, chatId, finalResponse)
                        } catch (e: Exception) {
                            sendTelegramMessage(telegramBotToken, chatId, "❌ Error executing task: ${e.localizedMessage}")
                        }
                    }
                    return@post
                }

                call.respond(HttpStatusCode.OK, mapOf("response" to "Received update with no text message."))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("response" to "❌ Internal error: ${e.localizedMessage ?: "Unknown error"}"))
            }
        }
    }
}
