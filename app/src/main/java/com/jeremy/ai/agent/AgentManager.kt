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

object AgentBridge {
    var agentManager: AgentManager? = null

    suspend fun processPrompt(prompt: String, onToken: (String) -> Unit): String {
        val manager = agentManager ?: return "Error: AgentManager not initialized."
        return manager.promptAgent(prompt, onToken)
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
        private val ACTION_KEYWORDS = listOf("open", "launch", "click", "tap", "type", "get", "check", "list", "show", "press", "find", "task:")
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

                val nativeLibDir = context.applicationInfo.nativeLibraryDir
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

    suspend fun promptAgent(prompt: String, onToken: (String) -> Unit = {}): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val localEngine = engine
            if (!isInitialized || localEngine == null) {
                return@withContext "Error: LiteRT-LM Engine offline or uninitialized."
            }

            val trimmedPrompt = prompt.trim()
            val lowerPrompt = trimmedPrompt.lowercase()

            val isActionCommand = ACTION_KEYWORDS.any { lowerPrompt.contains(it) }
            val isCasualChat = !isActionCommand && 
                               (lowerPrompt.matches(CASUAL_CHAT_REGEX) || lowerPrompt.split(" ").size <= 2)

            if (isCasualChat) {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful, concise AI assistant running locally on a Pixel 9. Respond in 1-2 brief English sentences only.")
                )
                
                localEngine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(Contents.of(trimmedPrompt))
                    val resultText = response.toString()
                    onToken(resultText)
                    return@withContext resultText
                }
            }

            // --- Performance-Optimized Autonomous Loop ---
            val maxSteps = 6
            val executionHistory = StringBuilder()
            val currentGoal = prompt

            for (step in 1..maxSteps) {
                val rawNotification = AgentNotificationListener.getContextSummary()
                val notificationContext = if (rawNotification.length > 200) rawNotification.take(200) + "..." else rawNotification

                val screenService = AgentAccessibilityService.instance
                val rawScreen = screenService?.getScreenContentSummary() ?: "Accessibility offline."
                val screenContext = if (rawScreen.length > 300) rawScreen.take(300) + "..." else rawScreen

                // Keep context window tight to maximize token generation performance and prevent KV-cache bloat
                val trimmedHistory = if (executionHistory.length > 600) {
                    executionHistory.takeLast(600)
                } else {
                    executionHistory.toString()
                }

                val systemInstructionText = """
                    You are an autonomous Android action engine. Execute composite tasks step-by-step.
                    [NOTIFICATIONS]: $notificationContext
                    [SCREEN HIERARCHY]: $screenContext
                    [HISTORY]: $trimmedHistory
                    
                    Rules:
                    - Output format:
                      TOOL: tool_name
                      ARG: argument_value
                    - Tools: open_browser, github_get_issues, get_screen_content, get_recent_notifications, open_app, click_text, type_text, tap_xy, scroll_down, scroll_up, go_home, go_back, open_recents, open_notifications, finish
                    - Use TOOL: finish with ARG: summary when done.
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
                    else -> rawToolName
                }

                if (toolName == "finish") {
                    val finalSummary = if (argValue.isNotBlank()) argValue else "Task successfully completed."
                    onToken(finalSummary)
                    return@withContext "Task Finished Successfully:\n$finalSummary\n\nExecution Log:\n$executionHistory"
                }

                val toolResult = toolRouter.executeToolCall("TOOL: $toolName\nARG: $argValue")
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
