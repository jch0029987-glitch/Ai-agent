package com.jeremy.ai.agent

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

class AgentToolRouter(
    private val context: Context,
    private val githubClient: GitHubToolClient
) {
    suspend fun executeToolCall(rawOutput: String): String {
        return try {
            val lines = rawOutput.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val toolLine = lines.find { it.startsWith("TOOL:") } ?: return "Error: No TOOL specified in output.\n\nRaw:\n$rawOutput"
            val argLine = lines.find { it.startsWith("ARG:") }

            val rawToolName = toolLine.removePrefix("TOOL:").trim().lowercase()
            val argValue = argLine?.removePrefix("ARG:")?.trim() ?: ""

            // Normalize minor model hallucinations / synonyms to actual tools
            val toolName = when (rawToolName) {
                "activate_network", "launch_browser", "open_url" -> "open_browser"
                "launch_app", "start_app" -> "open_app"
                "tap_text", "press_text" -> "click_text"
                "list_apps", "get_apps", "apps" -> "get_installed_apps"
                "notifications", "get_notifications", "check_notifications" -> "get_recent_notifications"
                "screen", "screen_content", "read_screen" -> "get_screen_content"
                else -> rawToolName
            }

            // 1. Human-in-the-Loop Guardrail: Intercept high-stakes or UI modification actions
            if (isSensitiveTool(toolName)) {
                val approved = requestUserApproval(toolName, argValue)
                if (!approved) {
                    return "Tool: $toolName\nResult: Error - Action denied by user security gate."
                }
            }

            // 2. Execute approved tool action
            val executionResult = when (toolName) {
                "open_browser" -> {
                    val url = if (argValue.isNotEmpty()) argValue else "https://google.com"
                    DeviceTools.openBrowser(context, url)
                }
                "github_get_issues" -> {
                    if (argValue.isBlank()) {
                        "Error: Repository argument is missing. Use 'owner/repo'."
                    } else {
                        val parts = argValue.split("/")
                        if (parts.size == 2) {
                            githubClient.getOpenIssues(parts[0], parts[1])
                        } else {
                            "Error: Invalid repository format. Use 'owner/repo'."
                        }
                    }
                }
                "get_screen_content" -> {
                    val screenService = AgentAccessibilityService.instance
                    screenService?.getScreenContentSummary() ?: "Error: Accessibility Service is offline."
                }
                "get_recent_notifications" -> {
                    DeviceTools.getRecentNotifications()
                }
                "click_text" -> {
                    if (argValue.isBlank()) {
                        "Error: No text provided to click."
                    } else {
                        val screenService = AgentAccessibilityService.instance
                        // Aligned with clickByText method in AgentAccessibilityService
                        if (screenService?.clickByText(argValue) == true) {
                            "Successfully clicked text: '$argValue'"
                        } else {
                            "Error: Failed to find or click text '$argValue' on screen."
                        }
                    }
                }
                "type_text" -> {
                    if (argValue.isBlank()) {
                        "Error: No text provided to type."
                    } else {
                        val screenService = AgentAccessibilityService.instance
                        if (screenService?.typeText(argValue) == true) {
                            "Successfully typed text into active field."
                        } else {
                            "Error: No focused or editable text field found to type into."
                        }
                    }
                }
                "open_app" -> {
                    if (argValue.isBlank()) {
                        "Error: No package name provided."
                    } else {
                        val screenService = AgentAccessibilityService.instance
                        val launched = screenService?.openApp(argValue) ?: false
                        if (launched) {
                            "Successfully launched package: $argValue"
                        } else {
                            // Fallback to traditional DeviceTools if accessibility service instance isn't bound yet
                            DeviceTools.openApp(context, argValue)
                        }
                    }
                }
                "get_installed_apps" -> {
                    DeviceTools.getInstalledApps(context)
                }
                "finish" -> {
                    argValue.ifBlank { "Task finished." }
                }
                else -> {
                    "Error: Unknown tool '$rawToolName'"
                }
            }

            "Tool: $toolName\nResult: $executionResult"

        } catch (e: Exception) {
            "Execution Error: ${e.localizedMessage}\n\nRaw Output:\n$rawOutput"
        }
    }

    private fun isSensitiveTool(toolName: String): Boolean {
        // Define which tools require manual tap approval via the overlay UI
        val sensitiveTools = listOf("open_app", "click_text", "type_text", "open_browser")
        return sensitiveTools.contains(toolName)
    }

    private suspend fun requestUserApproval(toolName: String, args: String): Boolean = withContext(Dispatchers.IO) {
        val future = CompletableFuture<Boolean>()
        ApprovalActivity.pendingApproval = future
        ApprovalActivity.toolDescription = "Autonomous Agent is requesting to execute:\n\nTool: $toolName\nArguments: $args"

        try {
            val intent = Intent(context, ApprovalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            
            // Blocks and suspends coroutine until user taps Approve or Deny on the overlay activity
            future.get()
        } catch (e: Exception) {
            false
        }
    }
}
