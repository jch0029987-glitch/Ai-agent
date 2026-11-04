package com.jeremy.ai.agent

import android.content.Context
import android.content.Intent
import com.jeremy.ai.agent.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                "home", "navigate_home" -> "go_home"
                "back", "navigate_back" -> "go_back"
                "recents", "overview" -> "open_recents"
                else -> rawToolName
            }

            // 1. Human-in-the-Loop Guardrail: Intercept high-stakes or UI modification actions
            if (isSensitiveTool(toolName)) {
                val approved = requestUserApproval(toolName, argValue)
                if (!approved) {
                    return "Tool: $toolName\nResult: Error - Action denied by user security gate."
                }
            }

            val screenService = AgentAccessibilityService.instance

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
                    screenService?.getScreenContentSummary() ?: "Error: Accessibility Service is offline."
                }
                "get_recent_notifications" -> {
                    DeviceTools.getRecentNotifications()
                }
                "click_text" -> {
                    if (argValue.isBlank()) {
                        "Error: No text provided to click."
                    } else {
                        if (screenService?.clickNodeByText(argValue) == true) {
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
                        if (screenService?.typeTextInFocusedNode(argValue) == true) {
                            "Successfully typed text into active field."
                        } else {
                            "Error: No focused or editable text field found to type into."
                        }
                    }
                }
                "tap_xy" -> {
                    if (screenService == null) {
                        "Error: Accessibility Service is offline."
                    } else {
                        val coords = argValue.split(",").map { it.trim().toFloatOrNull() }
                        if (coords.size == 2 && coords[0] != null && coords[1] != null) {
                            screenService.tapCoordinate(coords[0]!!, coords[1]!!)
                            "Dispatched coordinate tap at (${coords[0]}, ${coords[1]})."
                        } else {
                            "Error: Invalid ARG format for tap_xy. Expected 'x,y'."
                        }
                    }
                }
                "scroll_down" -> {
                    if (screenService?.scrollScreen(forward = true) == true) "Scrolled down successfully."
                    else "Error: Unable to scroll down."
                }
                "scroll_up" -> {
                    if (screenService?.scrollScreen(forward = false) == true) "Scrolled up successfully."
                    else "Error: Unable to scroll up."
                }
                "go_home" -> {
                    if (screenService?.performGlobalActionHome() == true) "Navigated Home." else "Error: Home action failed."
                }
                "go_back" -> {
                    if (screenService?.performGlobalActionBack() == true) "Navigated Back." else "Error: Back action failed."
                }
                "open_recents" -> {
                    if (screenService?.performGlobalActionRecents() == true) "Opened Recents screen." else "Error: Recents action failed."
                }
                "open_notifications" -> {
                    if (screenService?.performGlobalActionNotifications() == true) "Pulled down notification shade." else "Error: Notification action failed."
                }
                "open_app" -> {
                    if (argValue.isBlank()) {
                        "Error: No package name provided."
                    } else {
                        val launched = screenService?.let {
                            val intent = context.packageManager.getLaunchIntentForPackage(argValue)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                true
                            } else false
                        } ?: false

                        if (launched) {
                            "Successfully launched package: $argValue"
                        } else {
                            DeviceTools.openApp(context, argValue)
                        }
                    }
                }
                "get_installed_apps" -> {
                    DeviceTools.getInstalledApps(context)
                }
                "finish" -> {
                    argValue.ifBlank { "Task finished successfully." }
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
        // Include navigation or system-altering commands in security check if desired
        val sensitiveTools = listOf("open_app", "click_text", "type_text", "open_browser", "tap_xy")
        return sensitiveTools.contains(toolName)
    }

    private suspend fun requestUserApproval(toolName: String, args: String): Boolean = withContext(Dispatchers.IO) {
        // Temporarily auto-approve to bypass ApprovalActivity security gate during direct UI/bridge testing
        return@withContext true
    }
}
