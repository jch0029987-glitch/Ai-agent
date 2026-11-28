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
import com.jeremy.ai.backend.ShellSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AgentBridge {
    var agentManager: AgentManager? = null

    suspend fun processPrompt(prompt: String, onToken: (String) -> Unit): String {
        val manager = agentManager ?: return "Error: AgentManager not initialized."
        return manager.promptAgent(prompt, onToken)
    }

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
    private var loadedModelName: String? = null

    private var cachedMarkdownContext: String = ""
    private var lastMarkdownLoadMs: Long = 0L

    private val inferenceMutex = Mutex()

    private val githubClient = GitHubToolClient()
    private val toolRouter = AgentToolRouter(context, githubClient)

    init {
        ShellSessionManager.appContext = context.applicationContext
        AiBackendConfig.load(context)
        AgentBridge.agentManager = this
    }

    companion object {
        private const val TAG = "AgentManager"
        private const val DEFAULT_LITERT_MODEL = "gemma-4-E2B-it-gpu.litertlm"
        private const val MARKDOWN_CACHE_TTL_MS = 15_000L
        private const val MAX_AGENT_MD_CHARS = 2000
        private const val MAX_MEMORY_MD_CHARS = 2500

        private val CASUAL_CHAT_REGEX = Regex("^(hi|hello|hey|sup|what's up|who are you|help|thanks|thank you).*")
        private val ACTION_KEYWORDS = listOf(
            "open", "launch", "click", "tap", "type", "get", "check", "list", "show",
            "press", "find", "read", "patch", "write", "task:", "execute", "git", "shell",
            "search", "alarm", "schedule", "remind", "cron", "scan", "network", "adb",
            "roku", "kodi", "ssh", "ping", "tv", "pair"
        )
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun getBackendStatus(): String {
        val settings = AiBackendConfig.currentSettings
        val activeModel = settings.ollamaCoderModel.ifBlank { "qwen2.5-coder:3b" }
        val liteRtState = if (isInitialized && engine != null) {
            "ready (${loadedModelName ?: DEFAULT_LITERT_MODEL})"
        } else {
            "unloaded"
        }
        return "Mode: **${settings.mode.label}** (`${settings.mode.name}`)\n" +
            "LiteRT-LM: `$liteRtState`\n" +
            "Ollama: `$activeModel` @ `${settings.ollamaBaseUrl}`"
    }

    suspend fun switchToOllama(
        modelName: String? = null,
        baseUrl: String? = null,
        unloadLiteRt: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            val prev = AiBackendConfig.currentSettings
            val nextModel = modelName?.trim()?.takeIf { it.isNotEmpty() }
                ?: prev.ollamaCoderModel.ifBlank { "qwen2.5-coder:3b" }
            val nextUrl = baseUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
                ?: prev.ollamaBaseUrl

            val saved = AiBackendConfig.save(
                context,
                prev.copy(
                    mode = AiBackendMode.OLLAMA,
                    ollamaBaseUrl = nextUrl,
                    ollamaCoderModel = nextModel
                )
            )

            if (unloadLiteRt && isInitialized) {
                shutdownEngineInternal()
            }

            "Switched to **${saved.mode.label}** (`${saved.ollamaCoderModel}` @ `${saved.ollamaBaseUrl}`)."
        }
    }

    suspend fun switchToLiteRtLm(modelFileName: String? = null): String {
        val targetModel = modelFileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: loadedModelName
            ?: DEFAULT_LITERT_MODEL

        val prev = AiBackendConfig.currentSettings
        AiBackendConfig.save(
            context,
            prev.copy(mode = AiBackendMode.LITERT_LM)
        )

        val needsInit = !isInitialized || engine == null ||
            (modelFileName != null && loadedModelName != targetModel)

        if (!needsInit) {
            return "Switched to **Local LiteRT-LM** (`$targetModel`)."
        }

        val initResult = setupEngineFromStorage(targetModel)
        return if (initResult.isSuccess) {
            "Switched to **Local LiteRT-LM** and loaded `$targetModel`."
        } else {
            "Switched to **Local LiteRT-LM**, but failed to load `$targetModel`: ${initResult.exceptionOrNull()?.localizedMessage}"
        }
    }

    suspend fun setupEngineFromStorage(modelPathOrName: String = DEFAULT_LITERT_MODEL): Result<Boolean> =
        withContext(Dispatchers.IO) {
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
                        loadedModelName = null
                        return@withContext Result.failure(IllegalArgumentException(errorMsg))
                    }

                    if (!targetFile.canRead()) {
                        val errorMsg = "Permission denied: Cannot read file at ${targetFile.absolutePath}"
                        Log.e(TAG, errorMsg)
                        showToast("Permission denied reading $fileName")
                        isInitialized = false
                        loadedModelName = null
                        return@withContext Result.failure(SecurityException(errorMsg))
                    }

                    shutdownEngineInternal()

                    var activeBackendName = "GPU (Vulkan/OpenCL)"

                    val newEngine = try {
                        Log.i(TAG, "Initializing LiteRT-LM Engine on GPU backend...")
                        val gpuEngine = Engine(
                            EngineConfig(
                                modelPath = targetFile.absolutePath,
                                backend = Backend.GPU()
                            )
                        )
                        gpuEngine.initialize()
                        gpuEngine
                    } catch (eGpu: Exception) {
                        Log.w(TAG, "GPU backend unavailable or graph delegation failed, falling back to CPU", eGpu)
                        activeBackendName = "CPU"
                        val cpuEngine = Engine(
                            EngineConfig(
                                modelPath = targetFile.absolutePath,
                                backend = Backend.CPU()
                            )
                        )
                        cpuEngine.initialize()
                        cpuEngine
                    }

                    engine = newEngine
                    isInitialized = true
                    loadedModelName = fileName

                    Log.i(TAG, "LiteRT-LM successfully initialized on $activeBackendName!")
                    showToast("LiteRT-LM Engine Ready [$activeBackendName]")

                    Result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "LiteRT-LM Engine initialization failure: ${e.localizedMessage}", e)
                    showToast("Engine Init Failed: ${e.localizedMessage}")
                    isInitialized = false
                    loadedModelName = null
                    Result.failure(e)
                }
            }
        }

    private suspend fun runSingleLineShell(command: String): String {
        val sanitized = command.replace("\r", " ").replace("\n", " ").trim().removePrefix("\\").trim()
        return toolRouter.executeToolCall("TOOL: execute_shell\nARG: $sanitized")
    }

    suspend fun rememberNote(note: String): String = withContext(Dispatchers.IO) {
        val cleanNote = note.trim()
        if (cleanNote.isEmpty()) {
            return@withContext "Error: Cannot save an empty memory note."
        }
        val escapedNote = cleanNote.replace("'", "'\\''")
        val cmd = "mkdir -p ~/.agent/memory && printf -- '- %s\\n' '$escapedNote' >> ~/.agent/MEMORY.md"
        val result = runSingleLineShell(cmd)
        lastMarkdownLoadMs = 0L
        cachedMarkdownContext = ""
        if (result.contains("Error:", ignoreCase = true)) {
            "Error saving to ~/.agent/MEMORY.md: $result"
        } else {
            "Saved to `~/.agent/MEMORY.md`: $cleanNote"
        }
    }

    suspend fun readMemoryFiles(): String = withContext(Dispatchers.IO) {
        val cmd = "mkdir -p ~/.agent/memory && echo '=== ~/.agent/AGENT.md ===' && (cat ~/.agent/AGENT.md 2>/dev/null || echo '(empty)') && echo '=== ~/.agent/MEMORY.md ===' && (cat ~/.agent/MEMORY.md 2>/dev/null || echo '(empty)')"
        val output = runSingleLineShell(cmd).trim()
        if (output.isBlank()) {
            "No memory content returned from ~/.agent."
        } else {
            output
        }
    }

    private suspend fun loadMarkdownContext(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastMarkdownLoadMs < MARKDOWN_CACHE_TTL_MS && cachedMarkdownContext.isNotBlank()) {
            return@withContext cachedMarkdownContext
        }

        val shellCmd = "mkdir -p ~/.agent/memory && echo '===AGENT===' && (head -c $MAX_AGENT_MD_CHARS ~/.agent/AGENT.md 2>/dev/null || true) && echo '===MEMORY===' && (head -c $MAX_MEMORY_MD_CHARS ~/.agent/MEMORY.md 2>/dev/null || true)"

        val termuxResult = runCatching {
            runSingleLineShell(shellCmd)
        }.getOrNull().orEmpty()

        val parsed = if (termuxResult.contains("===AGENT===") || termuxResult.contains("===MEMORY===")) {
            val agentPart = termuxResult
                .substringAfter("===AGENT===", "")
                .substringBefore("===MEMORY===")
                .trim()
            val memoryPart = termuxResult
                .substringAfter("===MEMORY===", "")
                .trim()

            buildString {
                if (agentPart.isNotBlank() && !agentPart.contains("Error:", ignoreCase = true)) {
                    append("[AGENT.MD]:\n")
                    append(agentPart.take(MAX_AGENT_MD_CHARS))
                    append("\n\n")
                }
                if (memoryPart.isNotBlank() && !memoryPart.contains("Error:", ignoreCase = true)) {
                    append("[MEMORY.MD]:\n")
                    append(memoryPart.take(MAX_MEMORY_MD_CHARS))
                    append("\n\n")
                }
            }.trim()
        } else {
            val fallbackAgent = listOf(
                File(Environment.getExternalStorageDirectory(), "Download/models/AGENT.md"),
                File(context.filesDir, "AGENT.md")
            ).firstOrNull { it.exists() && it.canRead() }?.readText()?.trim()?.take(MAX_AGENT_MD_CHARS).orEmpty()

            val fallbackMemory = listOf(
                File(Environment.getExternalStorageDirectory(), "Download/models/MEMORY.md"),
                File(context.filesDir, "MEMORY.md")
            ).firstOrNull { it.exists() && it.canRead() }?.readText()?.trim()?.take(MAX_MEMORY_MD_CHARS).orEmpty()

            buildString {
                if (fallbackAgent.isNotBlank()) {
                    append("[AGENT.MD]:\n$fallbackAgent\n\n")
                }
                if (fallbackMemory.isNotBlank()) {
                    append("[MEMORY.MD]:\n$fallbackMemory\n\n")
                }
            }.trim()
        }

        cachedMarkdownContext = parsed
        lastMarkdownLoadMs = now
        parsed
    }

    private fun updateTelegramTicker(botToken: String?, chatId: Long?, messageId: Long?, statusText: String) {
        if (botToken.isNullOrBlank() || chatId == null || messageId == null || botToken == "YOUR_TELEGRAM_BOT_TOKEN") return
        try {
            val url = URL("https://api.telegram.org/bot$botToken/editMessageText")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val escapedText = statusText.replace("\"", "\\\"").replace("\n", "\\n")
            val payload = "{\"chat_id\": $chatId, \"message_id\": $messageId, \"text\": \"$escapedText\", \"parse_mode\": \"Markdown\"}"
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Telegram ticker", e)
        }
    }

    suspend fun promptAgent(
        prompt: String,
        onToken: (String) -> Unit = {}
    ): String = executePromptInternal(prompt, onToken, null, null, null)

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

    private suspend fun generateModelTurn(
        systemInstruction: String,
        userMessage: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {
        val settings = AiBackendConfig.currentSettings
        if (settings.mode == AiBackendMode.OLLAMA) {
            val baseUrl = settings.ollamaBaseUrl.trim().removeSuffix("/")
            val coderModel = settings.ollamaCoderModel.ifBlank { "qwen2.5-coder:3b" }
            val visionModel = settings.ollamaVisionModel

            val effectiveUserPrompt = if (!imageBase64.isNullOrBlank() && !visionModel.isNullOrBlank()) {
                val visionSummary = ollamaChatRequest(
                    baseUrl = baseUrl,
                    model = visionModel,
                    systemPrompt = "Extract all UI text, errors, layout structure, and visual facts accurately.",
                    userPrompt = userMessage,
                    imageBase64 = imageBase64,
                    keepAlive = 0
                )
                """
                Visual context ($visionModel):
                $visionSummary

                Request:
                $userMessage
                """.trimIndent()
            } else {
                userMessage
            }

            ollamaChatRequest(
                baseUrl = baseUrl,
                model = coderModel,
                systemPrompt = systemInstruction,
                userPrompt = effectiveUserPrompt,
                imageBase64 = null,
                keepAlive = "10m"
            )
        } else {
            val localEngine = engine ?: error("LiteRT-LM Engine offline or uninitialized.")
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemInstruction)
            )
            localEngine.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessage(Contents.of(userMessage)).toString()
            }
        }
    }

    private fun ollamaChatRequest(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        keepAlive: Any
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
                if (!imageBase64.isNullOrBlank()) {
                    put("images", JSONArray().put(imageBase64))
                }
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", false)
            put("keep_alive", keepAlive)
        }

        val conn = (URL("$baseUrl/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
        }

        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            error("Ollama HTTP $code: $body")
        }

        return JSONObject(body)
            .optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
    }

    private suspend fun executePromptInternal(
        prompt: String,
        onToken: (String) -> Unit,
        telegramBotToken: String?,
        telegramChatId: Long?,
        telegramMessageId: Long?
    ): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val activeSettings = AiBackendConfig.currentSettings
            if (activeSettings.mode == AiBackendMode.LITERT_LM && (!isInitialized || engine == null)) {
                return@withContext "Error: LiteRT-LM Engine offline or uninitialized."
            }

            if (telegramChatId != null) {
                TelegramChatContext.activeChatId = telegramChatId
            }
            if (!telegramBotToken.isNullOrBlank() && telegramBo
