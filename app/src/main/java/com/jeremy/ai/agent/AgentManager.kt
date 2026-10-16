package com.jeremy.ai.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.jeremy.ai.agent.services.AgentNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AgentManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    private var isInitialized = false

    private val githubClient = GitHubToolClient()
    private val toolRouter = AgentToolRouter(context, githubClient)

    suspend fun setupEngineFromStorage(modelPathOrName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (modelPathOrName.isBlank() || modelPathOrName == "model.gguf" || modelPathOrName == "model.bin") {
                File(context.filesDir, "model.bin").absolutePath
            } else {
                modelPathOrName
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(targetPath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isInitialized = false
            false
        }
    }

    suspend fun promptAgent(prompt: String, onToken: (String) -> Unit = {}): String = withContext(Dispatchers.Default) {
        val inference = llmInference
        if (!isInitialized || inference == null) return@withContext "Error: MediaPipe engine offline or uninitialized."

        val trimmedPrompt = prompt.trim()
        val lowerPrompt = trimmedPrompt.lowercase()

        // Action keywords that must trigger the Tool Agent Loop
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

        // --- Autonomous Multi-Step Tool Agent Loop ---
        val maxSteps = 6
        val executionHistory = StringBuilder()
        val currentGoal = prompt

        for (step in 1..maxSteps) {
            val notificationContext = AgentNotificationListener.getContextSummary()
            val screenService = AgentAccessibilityService.instance
            val screenContext = screenService?.getScreenContentSummary() ?: "Accessibility Service offline."

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

    fun shutdown() {
        if (isInitialized) {
            llmInference?.close()
            llmInference = null
            isInitialized = false
        }
    }
}
