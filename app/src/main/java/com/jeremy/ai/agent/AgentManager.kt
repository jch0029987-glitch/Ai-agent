package com.jeremy.ai.agent

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.jeremy.ai.agent.services.AgentNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
// BuildConfig is generated in this same package (com.jeremy.ai.agent), so no explicit import is required here!

object AgentBridge {
    var agentManager: AgentManager? = null

    // Exact 2-parameter signature for AgentViewModel and MatrixAgentBridge
    suspend fun processPrompt(prompt: String, onToken: (String) -> Unit): String {
        val manager = agentManager ?: return "Error: AgentManager not initialized."
        return manager.promptAgent(prompt, onToken)
    }

    // Dedicated signature for Telegram webhook routing without lambda/parameter collisions
    suspend fun processPromptWithTelegramUpdates(
        prompt: String,
        botToken: String,
        chatId: Long,
        messageId: Long
    ): String {
        val manager = agentManager ?: return "Error: AgentManager not initialized."
        return manager.promptAgentWithTelegram(prompt, botToken, chatId, messageId)
    }
}

class AgentManager(private val context: Context) {
    private var engine: Engine? = null
    private var isInitialized = false

    // Mutex lock to serialize native C++/JNI engine calls and prevent race crashes
    private val inferenceMutex = Mutex()

    private val githubClient = GitHubToolClient()
    private val toolRouter = AgentToolRouter(context, githubClient)

    init {
        AgentBridge.agentManager = this
    }

    companion object {
        private const val TAG = "AgentManager"
        private val CASUAL_CHAT_REGEX = Regex("^(hi|hello|hey|sup|what's up|who are you|help|thanks|thank you).*")
        private val ACTION_KEYWORDS = listOf("open", "launch", "click", "tap", "type", "get", "check", "list", "show", "press", "find", "read", "patch", "write", "task:", "execute", "git", "shell", "search")
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun setupEngineFromStorage(modelPathOrName: String = "gemma-4-E2B-it.litertlm"): Result<Boolean> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                val fileName = modelPathOrName.trim()

                val targetFile = if (fileName.startsWith("/")) {
                    File(fileName)
                } else {
                    val externalStorage = Environment.getExternalStorageDirectory()
                    val sdcardModelsFolder = File(externalStorage, "Download/models/$fileName")
                    val sdcardDownloadFolder = File(externalStorage, "Download/$fileName")
                    val internalFilesFolder = File(context.filesDir, fileName)

                    when {
                        sdcardModelsFolder.exists() -> sdcardModelsFolder
                        sdcardDownloadFolder.exists() -> sdcardDownloadFolder
                        else -> internalFilesFolder
                    }
                }

                Log.i(TAG, "Targeting model file: ${targetFile.absolutePath} (Exists: ${targetFile.exists()})")

                if (!targetFile.exists()) {
                    val errorMsg = "File does not exist at path: ${targetFile.absolutePath}"
                    Log.e(TAG, errorMsg)
                    showToast("Model file not found at $fileName")
                    isInitialized = false
                    return@withContext Result.failure(IllegalArgumentException(errorMsg))
                }

                if (!targetFile.canRead()) {
                    val errorMsg = "Permission denied: Cannot read file at ${targetFile.absolutePath}"
                    Log.e(TAG, errorMsg)
                    showToast("Permission denied reading $fileName")
                    isInitialized = false
                    return@withContext Result.failure(SecurityException(errorMsg))
                }

                shutdownEngineInternal()

                var activeBackendName = "GPU (Vulkan/OpenCL)"

                // Performance-optimized backend configuration: Prefer GPU delegate for maximum mobile throughput on Tensor G4, fallback to CPU
                val engineConfig = try {
                    Log.i(TAG, "Initializing LiteRT-LM Engine on GPU backend...")
                    EngineConfig(
                        modelPath = targetFile.absolutePath,
                        backend = Backend.GPU()
                    )
                } catch (eGpu: Exception) {
                    Log.w(TAG, "GPU backend unavailable, falling back to CPU", eGpu)
                    activeBackendName = "CPU"
                    EngineConfig(
                        modelPath = targetFile.absolutePath,
                        backend = Backend.CPU()
                    )
                }

                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                engine = newEngine
                isInitialized = true

                Log.i(TAG, "LiteRT-LM successfully initialized on $activeBackendName!")
                showToast("LiteRT-LM Engine Ready [$activeBackendName]")

                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM Engine initialization failure: ${e.localizedMessage}", e)
                showToast("Engine Init Failed: ${e.localizedMessage}")
                isInitialized = false
                Result.failure(e)
            }
        }
    }

    private fun updateTelegramTicker(botToken: String?, chatId: Long?, messageId: Long?, statusText: String) {
        if (botToken.isNullOrBlank() || chatId == null || messageId == null || botToken == "YOUR_TELEGRAM_BOT_TOKEN") return
        try {
            val url = java.net.URL("https://api.telegram.org/bot$botToken/editMessageText")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val escapedText = statusText.replace("\"", "\\\"").replace("\n", "\\n")
            val payload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"$escapedText\", \"parse_mode\": \"Markdown\"}"
            conn.outputStream.write(payload.toByteArray())
            conn.responseCode
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Telegram ticker", e)
        }
    }

    // Standard entry point used by AgentViewModel and MatrixAgentBridge
    suspend fun promptAgent(
        prompt: String,
        onToken: (String) -> Unit = {}
    ): String = executePromptInternal(prompt, onToken, null, null, null)

    // Dedicated entry point for Telegram webhooks with live tickers
    suspend fun promptAgentWithTelegram(
        prompt: String,
        telegramBotToken: String,
        telegramChatId: Long,
        telegramMessageId: Long
    ): String {
        val activeToken = if (telegramBotToken.isBlank() || telegramBotToken == "YOUR_TELEGRAM_BOT_TOKEN") {
            BuildConfig.TELEGRAM_BOT_TOKEN
        } else {
            telegramBotToken
        }
        TelegramChatContext.botToken = activeToken
        TelegramChatContext.activeChatId = telegramChatId
        return executePromptInternal(prompt, {}, activeToken, telegramChatId, telegramMessageId)
    }

    private suspend fun executePromptInternal(
        prompt: String,
        onToken: (String) -> Unit,
        telegramBotToken: String?,
        telegramChatId: Long?,
        telegramMessageId: Long?
    ): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val localEngine = engine
            if (!isInitialized || localEngine == null) {
                return@withContext "Error: LiteRT-LM Engine offline or uninitialized."
            }

            // Bind current Telegram session context for security approvals
            if (telegramChatId != null) {
                TelegramChatContext.activeChatId = telegramChatId
            }
            if (!telegramBotToken.isNullOrBlank() && telegramBotToken != "YOUR_TELEGRAM_BOT_TOKEN") {
                TelegramChatContext.botToken = telegramBotToken
            }

            val trimmedPrompt = prompt.trim()
            val lowerPrompt = trimmedPrompt.lowercase()

            val isActionCommand = ACTION_KEYWORDS.any { lowerPrompt.contains(it) }
            val isCasualChat = !isActionCommand && 
                               (lowerPrompt.matches(CASUAL_CHAT_REGEX) || lowerPrompt.split(" ").size <= 2)

            if (isCasualChat) {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful, concise AI coding assistant running locally via Termux & Ktor on a Pixel 9. Respond in 1-2 brief English sentences only.")
                )
                
                localEngine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(Contents.of(trimmedPrompt))
                    val resultText = response.toString()
                    onToken(resultText)
                    return@withContext resultText
                }
            }

            // --- Performance-Optimized Autonomous Coding Agent Loop ---
            val maxSteps = 8
            val executionHistory = StringBuilder()
            val currentGoal = prompt

            var lastToolName = ""
            var lastArgValue = ""

            for (step in 1..maxSteps) {
                val rawNotification = AgentNotificationListener.getContextSummary()
                val notificationContext = if (rawNotification.length > 200) rawNotification.take(200) + "..." else rawNotification

                val screenService = AgentAccessibilityService.instance
                val rawScreen = screenService?.getScreenContentSummary() ?: "Accessibility offline."
                val screenContext = if (rawScreen.length > 300) rawScreen.take(300) + "..." else rawScreen

                // Keep context window tight to maximize token generation performance and prevent KV-cache bloat
                val trimmedHistory = if (executionHistory.length > 800) {
                    executionHistory.takeLast(800)
                } else {
                    executionHistory.toString()
                }

                val systemInstructionText = """
                    You are an autonomous AI coding agent (Claude Code style) running on a local Android Termux & Ktor engine. Execute complex development and system tasks step-by-step.
                    [NOTIFICATIONS]: $notificationContext
                    [SCREEN HIERARCHY]: $screenContext
                    [HISTORY]: $trimmedHistory
                    
                    Rules:
                    - Output format:
                      TOOL: tool_name
                      ARG: argument_value
                    - Available Tools: execute_shell, patch_file, search_code, read_file, write_patch, open_browser, github_get_issues, get_screen_content, get_recent_notifications, open_app, get_installed_apps, click_text, type_text, tap_xy, scroll_down, scroll_up, go_home, go_back, finish
                    - Use TOOL: finish with ARG: summary when done. Do not repeat identical tool calls.
                """.trimIndent()

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemInstructionText)
                )

                val rawModelOutput = localEngine.createConversation(conversationConfig).use { conversation ->
                    conversation.sendMessage(Contents.of("Task: $currentGoal\nTOOL:"))
                }.toString()

                val toolIndex = rawModelOutput.indexOf("TOOL:")
                val cleanOutput = if (toolIndex != -1) rawModelOutput.substring(toolIndex) else rawModelOutput
                val formattedOutput = if (cleanOutput.startsWith("TOOL:")) cleanOutput else "TOOL:${cleanOutput.trimStart()}"

                val lines = formattedOutput.lines().map { it.trim() }
                val toolLine = lines.find { it.startsWith("TOOL:") } ?: "TOOL: unknown"
                val argLine = lines.find { it.startsWith("ARG:") }
                
                var rawToolName = toolLine.removePrefix("TOOL:").trim().lowercase()
                var argValue = argLine?.removePrefix("ARG:")?.trim() ?: ""

                if (rawToolName.contains("(")) {
                    val parenIndex = rawToolName.indexOf('(')
                    val innerArgs = rawToolName.substring(parenIndex + 1, rawToolName.lastIndexOf(')')).trim()
                        .removeSurrounding("\"").removeSurrounding("'")
                    rawToolName = rawToolName.substring(0, parenIndex).trim()
                    if (argValue.isBlank() && innerArgs.isNotBlank()) {
                        argValue = innerArgs
                    }
                }
                if (rawToolName.contains(":")) {
                    val parts = rawToolName.split(":").map { it.trim() }.filter { it.isNotEmpty() }
                    rawToolName = parts.last()
                }

                val toolName = when (rawToolName) {
                    "activate_network", "launch_browser", "open_url" -> "open_browser"
                    "launch_app", "start_app" -> "open_app"
                    "tap_text", "press_text" -> "click_text"
                    "list_apps", "get_apps", "apps", "open_apps" -> "get_installed_apps"
                    "notifications", "get_notifications", "check_notifications" -> "get_recent_notifications"
                    "screen", "screen_content", "read_screen" -> "get_screen_content"
                    "home", "navigate_home" -> "go_home"
                    "back", "navigate_back" -> "go_back"
                    "recents", "overview" -> "open_recents"
                    "cat", "view_file" -> "read_file"
                    "patch", "write_file", "save_file" -> "write_patch"
                    "shell", "bash", "cmd" -> "execute_shell"
                    else -> rawToolName
                }

                // Update Telegram progress ticker
                updateTelegramTicker(
                    botToken = telegramBotToken,
                    chatId = telegramChatId,
                    messageId = telegramMessageId,
                    statusText = "⚙️ *Step $step/$maxSteps:* Executing `$toolName($argValue)`"
                )

                if (toolName == "finish") {
                    val finalSummary = if (argValue.isNotBlank()) argValue else "Task successfully completed."
                    onToken(finalSummary)
                    return@withContext "Task Finished Successfully:\n$finalSummary\n\nExecution Log:\n$executionHistory"
                }

                // Guardrail: Intercept duplicate tool loops
                val toolResult = if (toolName == lastToolName && argValue == lastArgValue) {
                    "Error: Duplicate loop detected. You already called $toolName with argument '$argValue'. Try a different action or call finish."
                } else {
                    toolRouter.executeToolCall("TOOL: $toolName\nARG: $argValue")
                }

                lastToolName = toolName
                lastArgValue = argValue

                executionHistory.append("Step $step -> $toolName($argValue): $toolResult\n")

                if (step == maxSteps) {
                    return@withContext "Reached maximum iteration limit ($maxSteps steps).\n\nHistory:\n$executionHistory"
                }
            }

            return@withContext "Agent execution loop terminated."
        }
    }

    private fun shutdownEngineInternal() {
        if (isInitialized) {
            try {
                engine?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down LiteRT-LM Engine", e)
            } finally {
                engine = null
                isInitialized = false
            }
        }
    }

    fun shutdown() {
        shutdownEngineInternal()
    }
}
