package com.jeremy.ai.agent

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jeremy.ai.backend.PairingPromptManager
import com.jeremy.ai.backend.ShellSessionManager
import com.jeremy.ai.backend.TelegramApprovalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object TelegramChatContext {
    var activeChatId: Long? = null
    var botToken: String = ""
}

class AgentToolRouter(
    private val context: Context,
    private val githubClient: GitHubToolClient
) {
    init {
        ShellSessionManager.appContext = context.applicationContext
    }

    suspend fun executeToolCall(rawOutput: String): String {
        return try {
            val lines = rawOutput.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val toolLine = lines.find { it.startsWith("TOOL:") }
                ?: return "Error: No TOOL specified in output.\n\nRaw:\n$rawOutput"
            val argLine = lines.find { it.startsWith("ARG:") }

            val rawToolName = toolLine.removePrefix("TOOL:").trim().lowercase()
            val argValue = argLine?.removePrefix("ARG:")?.trim() ?: ""

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
                "write_patch", "patch_file" -> "write_patch"
                "shell", "bash", "cmd" -> "execute_shell"
                "alarm", "schedule", "remind", "reminder" -> "schedule_alarm"
                "cron", "cron_job" -> "add_cron_job"
                "scan", "network", "lan_scan" -> "network_scan"
                "ping", "services", "health_check" -> "service_ping"
                else -> rawToolName
            }

            if (isSensitiveTool(toolName)) {
                val approved = requestUserApproval(toolName, argValue)
                if (!approved) {
                    return "Tool: $toolName\nResult: Error - Action denied or timed out in user security gate."
                }
            }

            val screenService = AgentAccessibilityService.instance

            val executionResult = when (toolName) {
                "execute_shell" -> {
                    if (argValue.isBlank()) {
                        "Error: Shell command argument is missing."
                    } else {
                        ShellSessionManager.appContext = context.applicationContext
                        val shizukuOut = ShizukuBridge.executeCommand(argValue, context, timeoutSeconds = 45L)
                        if (shizukuOut != null) {
                            shizukuOut
                        } else {
                            ShellSessionManager.executeCommand(
                                context = context,
                                chatId = TelegramChatContext.activeChatId ?: 0L,
                                command = argValue,
                                timeoutSeconds = 45L
                            )
                        }
                    }
                }
                "network_scan" -> {
                    val argsJson = if (argValue.isBlank()) {
                        "{}"
                    } else if (argValue.trim().startsWith("{")) {
                        argValue
                    } else {
                        JSONObject().put("subnet_prefix", argValue.trim()).toString()
                    }
                    runAgentToolsScript("network_scan", argsJson, timeoutSeconds = 60L)
                }
                "service_ping" -> {
                    val argsJson = if (argValue.isBlank()) {
                        "{}"
                    } else if (argValue.trim().startsWith("{")) {
                        argValue
                    } else {
                        JSONObject().put("service", argValue.trim()).toString()
                    }
                    runAgentToolsScript("service_ping", argsJson, timeoutSeconds = 20L)
                }
                "adb" -> {
                    val argsJson = buildAdbArgsJson(argValue)
                    val firstOutput = runAgentToolsScript("adb", argsJson, timeoutSeconds = 90L)
                    if (looksLikeAdbPairingError(firstOutput)) {
                        handleAdbPairingFallback(argsJson, firstOutput)
                    } else {
                        firstOutput
                    }
                }
                "roku" -> {
                    val argsJson = buildDeviceActionArgsJson(
                        argValue = argValue,
                        defaultDevice = "roku",
                        defaultAction = "active_app",
                        actionKey = "action"
                    )
                    runAgentToolsScript("roku", argsJson, timeoutSeconds = 20L)
                }
                "kodi" -> {
                    val argsJson = buildDeviceActionArgsJson(
                        argValue = argValue,
                        defaultDevice = "kodi",
                        defaultAction = "Player.GetActivePlayers",
                        actionKey = "method"
                    )
                    runAgentToolsScript("kodi", argsJson, timeoutSeconds = 20L)
                }
                "ssh" -> {
                    val argsJson = buildSshArgsJson(argValue)
                    runAgentToolsScript("ssh", argsJson, timeoutSeconds = 30L)
                }
                "system_setting" -> {
                    SecureSettingsHelper.execute(context, argValue)
                }
                "dumpsys" -> {
                    runDumpsys(argValue)
                }
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
                        val file = if (argValue.startsWith("/")) {
                            File(argValue)
                        } else {
                            File(Environment.getExternalStorageDirectory(), argValue)
                        }
                        if (!file.exists()) {
                            "Error: File does not exist at path: ${file.absolutePath}"
                        } else {
                            file.readText().take(3000)
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
                            val file = if (path.startsWith("/")) {
                                File(path)
                            } else {
                                File(Environment.getExternalStorageDirectory(), path)
                            }
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
                            val botToken = resolveBotToken()

                            if (chatId != 0L && botToken.isNotBlank()) {
                                scheduleAlarmDirect(context, botToken, chatId, promptTask, triggerTime)
                                sendDirectTelegramMessage(
                                    botToken = botToken,
                                    chatId = chatId,
                                    text = "Successfully scheduled alarm for '$promptTask' in $timeSpec."
                                )
                                "Successfully scheduled alarm for '$promptTask' in $timeSpec."
                            } else {
                                "Error: Active Telegram chat context or bot token missing for alarm scheduling."
                            }
                        }
                    }
                }
                "add_cron_job" -> {
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

            val readableResult = formatToolOutput(toolName, executionResult).take(3500)
            "Tool: $toolName\nResult: $readableResult"
        } catch (e: Exception) {
            "Execution Error: ${e.localizedMessage}\n\nRaw Output:\n$rawOutput"
        }
    }

    private suspend fun runDumpsys(rawArg: String): String {
        val trimmed = rawArg.trim()
        val parsedService = if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed).optString("service", "") }.getOrDefault("")
        } else {
            trimmed.split(Regex("\\s+")).firstOrNull().orEmpty()
        }.trim()

        val service = parsedService.ifBlank { "battery" }.lowercase()
        val allowedServices = setOf(
            "battery",
            "batterystats",
            "meminfo",
            "activity",
            "window",
            "procstats",
            "deviceidle",
            "package"
        )
        if (service !in allowedServices) {
            return "Error: Unsupported dumpsys service '$service'. Allowed: ${allowedServices.joinToString(", ")}."
        }

        return try {
            val shizukuOut = ShizukuBridge.executeCommand("/system/bin/dumpsys $service", context, timeoutSeconds = 10L)
            val cleaned = if (shizukuOut != null) {
                shizukuOut.trim()
            } else {
                val process = ProcessBuilder("/system/bin/dumpsys", service)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return "Error: dumpsys '$service' timed out after 10s."
                }
                output.trim()
            }
            if (cleaned.isBlank()) {
                "No output from dumpsys $service."
            } else {
                cleaned.take(3000)
            }
        } catch (e: Exception) {
            "Error running dumpsys $service: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    private fun formatToolOutput(toolName: String, raw: String): String {
        val trimmed = raw.trim()
        val jsonObj = extractJsonObject(trimmed) ?: return trimmed.replace("\\n", "\n")

        val resultObj = jsonObj.optJSONObject("result") ?: jsonObj
        val ok = resultObj.optBoolean("ok", jsonObj.optBoolean("ok", true))
        val exitCode = resultObj.optInt("exit_code", Int.MIN_VALUE)
        val rawOutput = when {
            resultObj.has("output") -> resultObj.optString("output", "")
            resultObj.has("stdout") -> resultObj.optString("stdout", "")
            jsonObj.has("output") -> jsonObj.optString("output", "")
            else -> ""
        }.replace("\\n", "\n").trim()

        val errorText = resultObj.optString("error", jsonObj.optString("error", "")).trim()

        if (!ok || errorText.isNotEmpty() || (exitCode != Int.MIN_VALUE && exitCode != 0)) {
            val codePart = if (exitCode != Int.MIN_VALUE) " (exit $exitCode)" else ""
            val detail = listOfNotNull(
                errorText.takeIf { it.isNotEmpty() },
                rawOutput.takeIf { it.isNotEmpty() }
            ).joinToString("\n")
            return "Error$codePart: ${detail.ifBlank { trimmed }}"
        }

        return when (toolName) {
            "adb" -> {
                val device = jsonObj.optString(
                    "device",
                    resultObj.optString("device", "ADB device")
                )
                val focusedApp = Regex("""mFocusedApp=ActivityRecord\{[^ ]+\s+\S+\s+([^/\s}]+)""")
                    .find(rawOutput)?.groupValues?.getOrNull(1)
                val currentFocus = Regex("""mCurrentFocus=([^\n]+)""")
                    .find(rawOutput)?.groupValues?.getOrNull(1)?.trim()

                buildString {
                    append("Device: $device")
                    if (!focusedApp.isNullOrBlank()) {
                        append(" | Foreground app: $focusedApp")
                    }
                    if (!currentFocus.isNullOrBlank() && currentFocus != "null") {
                        append("\nCurrent focus: $currentFocus")
                    }
                    if (focusedApp.isNullOrBlank() && rawOutput.isNotBlank()) {
                        append("\n$rawOutput")
                    }
                }.trim()
            }
            else -> rawOutput.ifBlank {
                resultObj.toString(2)
            }
        }
    }

    private fun extractJsonObject(text: String): JSONObject? {
        if (text.startsWith("{")) {
            runCatching { return JSONObject(text) }
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end > start) {
            return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private suspend fun runAgentToolsScript(
        toolName: String,
        argsJson: String,
        timeoutSeconds: Long = 45L
    ): String = withContext(Dispatchers.IO) {
        ShellSessionManager.appContext = context.applicationContext
        val jobId = "job_${UUID.randomUUID().toString().take(8)}"
        val escapedArgs = argsJson.replace("'", "'\\''")
        val cmd = "/data/data/com.termux/files/usr/bin/python3 ~/agent_tools.py --job-id $jobId --tool $toolName --args-json '$escapedArgs'"
        ShellSessionManager.executeCommand(
            context = context,
            chatId = TelegramChatContext.activeChatId ?: 0L,
            command = cmd,
            timeoutSeconds = timeoutSeconds
        )
    }

    private fun buildAdbArgsJson(rawArg: String): String {
        val trimmed = rawArg.trim()
        if (trimmed.startsWith("{")) return trimmed
        if (trimmed.isBlank()) {
            return JSONObject()
                .put("device", "chromecast_hd")
                .put("action", "foreground_app")
                .toString()
        }
        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        return JSONObject()
            .put("device", parts[0])
            .put("action", parts.getOrElse(1) { "shell" })
            .put("arg", parts.getOrElse(2) { "" })
            .toString()
    }

    private fun buildDeviceActionArgsJson(
        argValue: String,
        defaultDevice: String,
        defaultAction: String,
        actionKey: String
    ): String {
        val trimmed = argValue.trim()
        if (trimmed.startsWith("{")) return trimmed
        if (trimmed.isBlank()) {
            return JSONObject()
                .put("device", defaultDevice)
                .put(actionKey, defaultAction)
                .toString()
        }
        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        return JSONObject()
            .put("device", parts[0])
            .put(actionKey, parts.getOrElse(1) { defaultAction })
            .put("arg", parts.getOrElse(2) { "" })
            .toString()
    }

    private fun buildSshArgsJson(rawArg: String): String {
        val trimmed = rawArg.trim()
        if (trimmed.startsWith("{")) return trimmed
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        return JSONObject()
            .put("host", parts.firstOrNull().orEmpty())
            .put("command", parts.getOrElse(1) { "uptime" })
            .toString()
    }

    private fun looksLikeAdbPairingError(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("connection refused") ||
            lower.contains("not found") ||
            lower.contains("unauthorized") ||
            lower.contains("device offline") ||
            lower.contains("protocol fault") ||
            lower.contains("failed to connect")
    }

    private suspend fun handleAdbPairingFallback(originalArgsJson: String, firstOutput: String): String =
        withContext(Dispatchers.IO) {
            val chatId = TelegramChatContext.activeChatId
            val botToken = resolveBotToken()
            if (chatId == null || botToken.isBlank()) {
                return@withContext "$firstOutput\n(Pairing needed, but no active Telegram chat/token is available.)"
            }

            val deviceName = runCatching {
                JSONObject(originalArgsJson).optString("device", "chromecast_hd")
            }.getOrDefault("chromecast_hd")

            sendDirectTelegramMessage(
                botToken = botToken,
                chatId = chatId,
                text = "ADB connection/pairing needed for `$deviceName`.\n" +
                    "What is the **pairing port** and **6-digit pairing code**?\n" +
                    "Reply with: `<pairing_port> <code>` (or `<pairing_port> <code> <connect_port>`), or `cancel`."
            )

            val reply = PairingPromptManager.awaitWithTimeout(chatId.toString(), timeoutSec = 90L)
            if (reply.isNullOrBlank() || reply.equals("cancel", ignoreCase = true)) {
                return@withContext "$firstOutput\nPairing cancelled or timed out."
            }

            val pairArgsJson = JSONObject()
                .put("device", deviceName)
                .put("action", "pair")
                .put("arg", reply)
                .toString()

            val pairResult = runAgentToolsScript("adb", pairArgsJson, timeoutSeconds = 30L)
            if (pairResult.contains("\"ok\": false") || pairResult.contains("error", ignoreCase = true)) {
                return@withContext "Pairing attempt output:\n$pairResult"
            }

            val retryResult = runAgentToolsScript("adb", originalArgsJson, timeoutSeconds = 90L)
            "Paired via Telegram input (`$reply`). Retry result:\n$retryResult"
        }

    private fun resolveBotToken(): String {
        return if (TelegramChatContext.botToken.isNotBlank()) {
            TelegramChatContext.botToken
        } else {
            try {
                BuildConfig.TELEGRAM_BOT_TOKEN
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun sendDirectTelegramMessage(botToken: String, chatId: Long, text: String) {
        try {
            val url = java.net.URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            val payload = "{\"chat_id\": $chatId, \"text\": \"$escaped\", \"parse_mode\": \"Markdown\"}"
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun scheduleAlarmDirect(
        context: Context,
        botToken: String,
        chatId: Long,
        prompt: String,
        triggerAtMillis: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("prompt", prompt)
            putExtra("bot_token", botToken)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (chatId + triggerAtMillis).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun parseTimeSpecToMillis(spec: String): Long {
        val clean = spec.lowercase().removePrefix("in").trim()
        val number = clean.filter { it.isDigit() }.toLongOrNull() ?: 10
        return when {
            clean.contains("s") -> number * 1000
            clean.contains("h") -> number * 60 * 60 * 1000
            else -> number * 60 * 1000
        }
    }

    private fun isSensitiveTool(toolName: String): Boolean {
        val sensitiveTools = listOf(
            "open_app", "click_text", "type_text", "open_browser", "tap_xy", "write_patch", "ssh", "system_setting", "dumpsys"
        )
        return sensitiveTools.contains(toolName)
    }

    private suspend fun requestUserApproval(toolName: String, args: String): Boolean = withContext(Dispatchers.IO) {
        val chatId = TelegramChatContext.activeChatId
        val botToken = resolveBotToken()

        if (chatId == null || botToken.isBlank()) {
            return@withContext true
        }

        val taskId = UUID.randomUUID().toString()
        val future = CompletableFuture<Boolean>()
        TelegramApprovalStore.pendingApprovals[taskId] = future

        val messageText = "⚠️ *Security Gate: Action Approval Required*\n\nTool: `$toolName`\nArgs: `$args`"
        val payload = """
            {
                "chat_id": $chatId,
                "text": "${messageText.replace("\"", "\\\"").replace("\n", "\\n")}",
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
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()

            return@withContext try {
                future.get(120, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                TelegramApprovalStore.pendingApprovals.remove(taskId)
                false
            }
        } catch (_: Exception) {
            TelegramApprovalStore.pendingApprovals.remove(taskId)
            return@withContext false
        }
    }
}

private object SecureSettingsHelper {
    private const val MAX_HISTORY_ENTRIES = 50

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun historyFile(context: Context): File {
        val preferredDir = File(Environment.getExternalStorageDirectory(), ".agent")
        val baseDir = if (preferredDir.exists() || preferredDir.mkdirs()) {
            preferredDir
        } else {
            context.filesDir
        }
        return File(baseDir, "settings_history.json")
    }

    private fun readHistory(context: Context): JSONArray {
        val file = historyFile(context)
        if (!file.exists()) return JSONArray()
        return runCatching {
            JSONArray(file.readText().ifBlank { "[]" })
        }.getOrDefault(JSONArray())
    }

    private fun writeHistory(context: Context, array: JSONArray) {
        val trimmed = JSONArray()
        val startIdx = (array.length() - MAX_HISTORY_ENTRIES).coerceAtLeast(0)
        for (i in startIdx until array.length()) {
            trimmed.put(array.get(i))
        }
        runCatching {
            historyFile(context).writeText(trimmed.toString(2))
        }
    }

    fun getSetting(context: Context, table: String, key: String): String {
        val resolver = context.contentResolver
        return when (table.lowercase()) {
            "secure" -> Settings.Secure.getString(resolver, key) ?: "<null>"
            "global" -> Settings.Global.getString(resolver, key) ?: "<null>"
            "system" -> Settings.System.getString(resolver, key) ?: "<null>"
            else -> "Error: Unsupported settings table '$table'. Use secure, global, or system."
        }
    }

    private fun putRaw(context: Context, table: String, key: String, value: String?): Boolean {
        val resolver = context.contentResolver
        return when (table.lowercase()) {
            "secure" -> Settings.Secure.putString(resolver, key, value)
            "global" -> Settings.Global.putString(resolver, key, value)
            "system" -> Settings.System.putString(resolver, key, value)
            else -> false
        }
    }

    fun execute(context: Context, rawArg: String): String {
        val parsed = parseArgs(rawArg)
        val action = parsed.optString("action", "get").lowercase()
        val table = parsed.optString("table", "global").lowercase()
        val key = parsed.optString("key", "").trim()

        if (action == "rollback") {
            return rollbackLastChange(context, key.ifBlank { null })
        }

        if (key.isEmpty()) {
            return "Error: Missing 'key' in system_setting ARG."
        }

        if (action == "get") {
            val current = getSetting(context, table, key)
            return "[$table/$key] = $current"
        }

        if (!hasWriteSecureSettings(context)) {
            return "Error: Missing WRITE_SECURE_SETTINGS permission. Grant via ADB/Shizuku: pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        }

        val previousValue = getSetting(context, table, key)
        val previousNullable = if (previousValue == "<null>") null else previousValue

        return when (action) {
            "put", "set" -> {
                if (!parsed.has("value")) {
                    return "Error: Missing 'value' for action '$action'."
                }
                val newValue = parsed.optString("value")
                val ok = putRaw(context, table, key, newValue)
                if (!ok) {
                    return "Error: Failed to write [$table/$key] = '$newValue'."
                }
                recordHistory(context, table, key, previousNullable, newValue)
                "Updated [$table/$key]: '$previousValue' -> '$newValue'"
            }
            "delete", "clear" -> {
                val ok = putRaw(context, table, key, null)
                if (!ok) {
                    return "Error: Failed to clear [$table/$key]."
                }
                recordHistory(context, table, key, previousNullable, null)
                "Cleared [$table/$key] (previous value: '$previousValue')"
            }
            else -> "Error: Unsupported action '$action'. Use get, put, delete, or rollback."
        }
    }

    private fun recordHistory(
        context: Context,
        table: String,
        key: String,
        oldValue: String?,
        newValue: String?
    ) {
        val arr = readHistory(context)
        val entry = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("table", table)
            .put("key", key)
            .put("old_value", oldValue ?: JSONObject.NULL)
            .put("new_value", newValue ?: JSONObject.NULL)
        arr.put(entry)
        writeHistory(context, arr)
    }

    private fun rollbackLastChange(context: Context, targetKey: String?): String {
        if (!hasWriteSecureSettings(context)) {
            return "Error: Missing WRITE_SECURE_SETTINGS permission."
        }
        val arr = readHistory(context)
        if (arr.length() == 0) {
            return "No recorded setting changes available to roll back."
        }

        for (i in arr.length() - 1 downTo 0) {
            val item = arr.getJSONObject(i)
            val key = item.optString("key")
            if (targetKey != null && !key.equals(targetKey, ignoreCase = true)) {
                continue
            }
            val table = item.optString("table", "global")
            val oldValue = if (item.isNull("old_value")) null else item.optString("old_value")
            val ok = putRaw(context, table, key, oldValue)
            if (!ok) {
                return "Error: Failed to roll back [$table/$key]."
            }
            val updated = JSONArray()
            for (j in 0 until arr.length()) {
                if (j != i) updated.put(arr.get(j))
            }
            writeHistory(context, updated)
            return "Rolled back [$table/$key] to '${oldValue ?: "<null>"}'."
        }

        return if (targetKey != null) {
            "No history entry found for key '$targetKey'."
        } else {
            "No recorded setting changes available to roll back."
        }
    }

    private fun parseArgs(rawArg: String): JSONObject {
        val trimmed = rawArg.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrElse { JSONObject() }
        }
        val parts = trimmed.split(Regex("\\s+"), limit = 4)
        val obj = JSONObject()
        if (parts.isNotEmpty() && parts[0].isNotBlank()) obj.put("table", parts[0])
        if (parts.size >= 2 && parts[1].isNotBlank()) obj.put("action", parts[1])
        if (parts.size >= 3 && parts[2].isNotBlank()) obj.put("key", parts[2])
        if (parts.size >= 4) obj.put("value", parts[3])
        return obj
    }
}
