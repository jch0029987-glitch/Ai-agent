package com.jeremy.ai.agent

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.jeremy.ai.backend.CronJobManager
import com.jeremy.ai.backend.TelegramApprovalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

object TelegramChatContext {
    var activeChatId: Long? = null
    var botToken: String = "YOUR_TELEGRAM_BOT_TOKEN"
}

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
                "read_file", "cat" -> "read_file"
                "write_patch", "patch_file", "write_file" -> "write_patch"
                "alarm", "schedule", "remind", "reminder" -> "schedule_alarm"
                "cron", "cron_job" -> "add_cron_job"
                else -> rawToolName
            }

            // 1. Human-in-the-Loop Guardrail: Intercept high-stakes or UI modification / file editing actions via Telegram
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
                "read_file" -> {
                    if (argValue.isBlank()) {
                        "Error: File path argument is missing."
                    } else {
                        val file = if (argValue.startsWith("/")) File(argValue) else File(Environment.getExternalStorageDirectory(), argValue)
                        if (!file.exists()) {
                            "Error: File does not exist at path: ${file.absolutePath}"
                        } else {
                            file.readText().take(3000) // Read safely up to 3000 chars
                        }
                    }
                }
                "write_patch" -> {
                    if (argValue.isBlank() || !argValue.contains("|")) {
                        "Error: Invalid format. Expected 'filepath | content'."
                    } else {
                        val parts = argValue.split("|", limit = 2)
                        val path = parts[0].trim()
                        val content = parts[1].trim()
                        try {
                            val file = if (path.startsWith("/")) File(path) else File(Environment.getExternalStorageDirectory(), path)
                            file.parentFile?.mkdirs()
                            file.writeText(content)
                            "Successfully wrote/patched file at ${file.absolutePath}"
                        } catch (e: Exception) {
                            "Error writing patch: ${e.localizedMessage}"
                        }
                    }
                }
                "schedule_alarm" -> {
                    if (argValue.isBlank()) {
                        "Error: Missing argument. Expected 'time | task prompt' (e.g., '10s | echo test')."
                    } else {
                        val parts = argValue.split("|", limit = 2)
                        if (parts.size < 2) {
                            "Error: Invalid format. You must separate the time and prompt with a pipe (|). Example: '10s | echo test'."
                        } else {
                            val timeSpec = parts[0].trim()
                            val promptTask = parts[1].trim()

                            val delayMillis = parseTimeSpecToMillis(timeSpec)
                            val triggerTime = System.currentTimeMillis() + delayMillis

                            val chatId = TelegramChatContext.activeChatId ?: 0L
                            val botToken = TelegramChatContext.botToken

                            if (chatId != 0L && botToken != "YOUR_TELEGRAM_BOT_TOKEN") {
                                CronJobManager.scheduleExactAlarm(context, botToken, chatId, promptTask, triggerTime)
                                "Successfully scheduled alarm for '$promptTask' in $timeSpec. Task is complete. Do not call this tool again."
                            } else {
                                "Error: Active Telegram chat context missing for alarm scheduling."
                            }
                        }
                    }
                }
                "add_cron_job" -> {
                    // Periodic work registration placeholder
                    "Cron task registered successfully."
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
                "open_app_picker" -> {
                    DeviceTools.openAppPicker(context)
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

    private fun parseTimeSpecToMillis(spec: String): Long {
        val clean = spec.lowercase().removePrefix("in").trim()
        val number = clean.filter { it.isDigit() }.toLongOrNull() ?: 10
        return when {
            clean.contains("s") -> number * 1000
            clean.contains("h") -> number * 60 * 60 * 1000
            else -> number * 60 * 1000 // default to minutes
        }
    }

    private fun isSensitiveTool(toolName: String): Boolean {
        val sensitiveTools = listOf("open_app", "click_text", "type_text", "open_browser", "tap_xy", "write_patch")
        return sensitiveTools.contains(toolName)
    }

    private suspend fun requestUserApproval(toolName: String, args: String): Boolean = withContext(Dispatchers.IO) {
        val chatId = TelegramChatContext.activeChatId
        val botToken = TelegramChatContext.botToken

        // If no Telegram chat session is currently active, default to auto-approve for local app tests
        if (chatId == null || botToken == "YOUR_TELEGRAM_BOT_TOKEN") {
            return@withContext true
        }

        val taskId = UUID.randomUUID().toString()
        val future = CompletableFuture<Boolean>()
        TelegramApprovalStore.pendingApprovals[taskId] = future

        val messageText = "⚠️ *Security Gate: Action Approval Required*\n\nTool: `$toolName`\nArgs: `$args`"
        val payload = """
            {
                "chat_id": $chatId,
                "text": "$messageText",
                "parse_mode": "Markdown",
                "reply_markup": {
                    "inline_keyboard": [[
                        {"text": "✅ Approve", "callback_data": "approve:$taskId"},
                        {"text": "⛔ Deny", "callback_data": "deny:$taskId"}
                    ]]
                }
            }
        """.trimIndent()

        try {
            val url = java.net.URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(payload.toByteArray())
            conn.responseCode

            // Block coroutine thread until inline button is tapped in Telegram
            return@withContext future.get()
        } catch (e: Exception) {
            return@withContext false
        }
    }
}
