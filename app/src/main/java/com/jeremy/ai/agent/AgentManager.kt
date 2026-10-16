package com.jeremy.ai.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.LlmInference
import com.jeremy.ai.agent.services.AgentNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AgentManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    private var isInitialized = false

    // Strict mutex lock to serialize all C++/JNI engine calls and prevent concurrency crashes
    private val inferenceMutex = Mutex()

    private val githubClient = GitHubToolClient()
    private val toolRouter = AgentToolRouter(context, githubClient)

    companion object {
        private const val TAG = "AgentManager"
    }

    suspend fun setupEngineFromStorage(modelPathOrName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                val fileName = modelPathOrName.trim()

                // 1. Resolve target file path cleanly using absolute paths
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

                Log.i(TAG, "Targeting LiteRT-LM model file: ${targetFile.absolutePath} (Exists: ${targetFile.exists()})")

                if (!targetFile.exists()) {
                    val errorMsg = "File does not exist at path: ${targetFile.absolutePath}"
                    Log.e(TAG, errorMsg)
                    isInitialized = false
                    return@withContext Result.failure(IllegalArgumentException(errorMsg))
                }

                if (!targetFile.canRead()) {
                    val errorMsg = "Permission denied: Cannot read file at ${targetFile.absolutePath}"
                    Log.e(TAG, errorMsg)
                    isInitialized = false
                    return@withContext Result.failure(SecurityException(errorMsg))
                }

                // Close any existing native engine handles before initializing
                shutdownEngineInternal()

                // 2. Configure LiteRT-LM LlmInference Options
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(targetFile.absolutePath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.GPU) // Can fall back to CPU if driver issues persist
                    .build()

                Log.i(TAG, "Initializing LiteRT-LM runtime backend...")
                llmInference = LlmInference.createFromOptions(context, options)
                isInitialized = true
                Log.i(TAG, "LiteRT-LM engine successfully initialized!")

                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM engine initialization failure: ${e.localizedMessage}", e)
                isInitialized = false
                Result.failure(e)
            }
        }
    }

    suspend fun promptAgent(prompt: String, onToken: (String) -> Unit = {}): String = withContext(Dispatchers.Default) {
        // Enforce thread serialization: wait for any ongoing prompt generation to release the native JNI handle
        inferenceMutex.withLock {
            val inference = llmInference
            if (!isInitialized || inference == null) {
                return@withContext "Error: LiteRT-LM engine offline or uninitialized."
            }

            val trimmedPrompt = prompt.trim()
            val lowerPrompt = trimmedPrompt.lowercase()

            val actionKeywords = listOf("open", "launch", "click", "tap", "type", "get", "check", "list", "show", "press", "find", "task:")
            val isActionCommand = actionKeywords.any { lowerPrompt.contains(it) }

            val isCasualChat = !isActionCommand && 
                               (lowerPrompt.matches(Regex("^(hi|hello|hey|sup|what's up|who are you|help|thanks|thank you).*")) || lowerPrompt.split(" ").size <= 2)

            if (isCasualChat) {
                val chatPrompt = """
                    <|im_start|>system
                    You are a helpful, concise AI assistant running locally on a Pixel 9 device. Respond in 1-2 brief English sentences only.
                    <|im_end|>
                    <|im_start|>user
                    $trimmedPrompt
                    <|im_end|>
                    <|im_start|>assistant
                """.trimIndent()

                val response = inference.generateResponse(chatPrompt)
                onToken(response)
                return@withContext response
            }

            // --- Autonomous Agent Execution Loop ---
            val maxSteps = 6
            val executionHistory = StringBuilder()
            val currentGoal = prompt

            for (step in 1..maxSteps) {
                // Truncate screen and notification summaries to prevent context window token overflow
                val rawNotification = AgentNotificationListener.getContextSummary()
                val notificationContext = if (rawNotification.length > 300) rawNotification.take(300) + "... [truncated]" else rawNotification

                val screenService = AgentAccessibilityService.instance
                val rawScreen = screenService?.getScreenContentSummary() ?: "Accessibility Service offline."
                val screenContext = if (rawScreen.length > 500) rawScreen.take(500) + "... [truncated]" else rawScreen

                val systemPrompt = """
                    <|im_start|>system
                    You are an autonomous Android action engine. You execute composite tasks step-by-step using tools.
                    
                    [RECENT NOTIFICATIONS]
                    $notificationContext
                    
                    [CURRENT SCREEN HIERARCHY]
                    $screenContext
                    
                    [EXECUTION HISTORY]
                    $executionHistory
                    
                    Rules:
                    - Output only a single TOOL and ARG pair per turn using format:
                      TOOL: tool_name
                      ARG: argument_value
                    - Available tools: open_browser, github_get_issues, get_screen_content, get_recent_notifications, open_app, click_text, type_text, get_installed_apps, finish.
                    - When the overall task is completely fulfilled, use TOOL: finish with ARG: summary of results.
                    <|im_end|>
                    <|im_start|>user
                    Task: $currentGoal
                    <|im_end|>
                    <|im_start|>assistant
                    TOOL:
                """.trimIndent()

                val rawModelOutput = inference.generateResponse(systemPrompt)

                val toolIndex = rawModelOutput.indexOf("TOOL:")
                val cleanOutput = if (toolIndex != -1) rawModelOutput.substring(toolIndex) else rawModelOutput

                val formattedOutput = if (cleanOutput.startsWith("TOOL:")) {
                    cleanOutput
                } else {
                    "TOOL:${cleanOutput.trimStart()}"
                }

                val lines = formattedOutput.lines().map { it.trim() }
                val toolLine = lines.find { it.startsWith("TOOL:") } ?: "TOOL: unknown"
                val argLine = lines.find { it.startsWith("ARG:") }
                
                val rawToolName = toolLine.removePrefix("TOOL:").trim().lowercase()
                val argValue = argLine?.removePrefix("ARG:")?.trim() ?: ""

                val toolName = when (rawToolName) {
                    "activate_network", "launch_browser", "open_url" -> "open_browser"
                    "launch_app", "start_app" -> "open_app"
                    "tap_text", "press_text" -> "click_text"
                    "list_apps", "get_apps", "apps", "open_apps" -> "get_installed_apps"
                    "notifications", "get_notifications", "check_notifications" -> "get_recent_notifications"
                    else -> rawToolName
                }

                if (toolName == "finish") {
                    val finalSummary = if (argValue.isNotBlank()) argValue else "Task successfully completed."
                    return@withContext "Task Finished Successfully:\n$finalSummary\n\nExecution Log:\n$executionHistory"
                }

                val toolResult = toolRouter.executeToolCall("TOOL: $toolName\nARG: $argValue")
                executionHistory.append("Step $step -> TOOL: $toolName (ARG: $argValue)\nResult: $toolResult\n\n")

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
                llmInference?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing LiteRT-LM instance", e)
            } finally {
                llmInference = null
                isInitialized = false
            }
        }
    }

    fun shutdown() {
        shutdownEngineInternal()
    }
}
