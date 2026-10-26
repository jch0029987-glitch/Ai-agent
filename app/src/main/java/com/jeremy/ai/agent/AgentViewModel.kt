package com.jeremy.ai.agent

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeremy.ai.agent.data.AppDatabase
import com.jeremy.ai.agent.data.ChatMessageEntity
import com.jeremy.ai.agent.data.ChatSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessageItem(val sender: String, val text: String)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessageItem> = emptyList()
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context get() = getApplication<Application>()
    private val agentManager = AgentManager(application)
    private val chatDao = AppDatabase.getDatabase(application).chatDao()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    var sessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    var currentSessionId by mutableStateOf<String?>(null)
        private set

    // Live state tracking whether the embedded Ktor server is active on port 8080
    var isServerRunning by mutableStateOf(false)
        private set

    val currentSession: ChatSession?
        get() = sessions.find { it.id == currentSessionId }

    init {
        // Start background polling to monitor Ktor server health / port availability
        startServerHealthPoll()

        // Collect session threads reactively from Room
        viewModelScope.launch {
            chatDao.getAllSessions().collectLatest { sessionEntities ->
                if (sessionEntities.isEmpty()) {
                    createNewSession("Default Thread")
                } else {
                    val updatedSessions = sessionEntities.map { entity ->
                        val existing = sessions.find { it.id == entity.id }
                        ChatSession(
                            id = entity.id,
                            title = entity.title,
                            messages = existing?.messages ?: emptyList()
                        )
                    }
                    sessions = updatedSessions
                    if (currentSessionId == null && updatedSessions.isNotEmpty()) {
                        selectSession(updatedSessions.first().id)
                    }
                }
            }
        }
    }

    private fun startServerHealthPoll() {
        viewModelScope.launch {
            while (true) {
                isServerRunning = checkLocalPort(8080)
                delay(3000) // Poll every 3 seconds
            }
        }
    }

    private suspend fun checkLocalPort(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket("127.0.0.1", port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dumps error logs and stack traces directly to internal app storage
     * so they can be viewed securely in the ErrorLogViewerDialog.
     */
    fun dumpErrorToStorage(tag: String, throwable: Throwable) {
        try {
            val logFile = File(appContext.filesDir, "agent_error_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val errorContent = buildString {
                appendLine("========================================")
                appendLine("TIMESTAMP: $timestamp")
                appendLine("TAG: $tag")
                appendLine("ERROR: ${throwable.localizedMessage}")
                appendLine("STACKTRACE:")
                appendLine(throwable.stackTraceToString())
                appendLine("========================================\n")
            }
            
            logFile.appendText(errorContent)
            Log.e(tag, "Logged error to internal storage: ${logFile.absolutePath}", throwable)
        } catch (e: Exception) {
            Log.e("AgentViewModel", "Failed to write internal error log", e)
        }
    }

    fun createNewSession(title: String = "Thread ${sessions.size + 1}") {
        viewModelScope.launch {
            try {
                val newEntity = ChatSessionEntity(title = title)
                chatDao.insertSession(newEntity)
                selectSession(newEntity.id)
            } catch (e: Exception) {
                dumpErrorToStorage("createNewSession", e)
            }
        }
    }

    fun selectSession(sessionId: String) {
        currentSessionId = sessionId
        viewModelScope.launch {
            try {
                chatDao.getMessagesForSession(sessionId).collectLatest { messageEntities ->
                    val mappedMessages = messageEntities.map { ChatMessageItem(it.sender, it.text) }
                    sessions = sessions.map { session ->
                        if (session.id == sessionId) {
                            session.copy(messages = mappedMessages)
                        } else {
                            session
                        }
                    }
                }
            } catch (e: Exception) {
                dumpErrorToStorage("selectSession", e)
            }
        }
    }

    fun initializeModelFromDownloads(fileName: String = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm") {
        viewModelScope.launch {
            _uiState.value = AgentUiState.Loading("Connecting to local engine...")
            try {
                val result = agentManager.setupEngineFromStorage(fileName)
                
                _uiState.value = if (result.isSuccess) {
                    AgentUiState.Ready
                } else {
                    val exception = result.exceptionOrNull() ?: Exception("Unknown initialization error")
                    dumpErrorToStorage("initializeModelFromDownloads", exception)
                    AgentUiState.Error(exception.localizedMessage ?: "Could not connect to engine backend.")
                }
            } catch (e: Throwable) {
                dumpErrorToStorage("initializeModelFromDownloads_Crash", e)
                _uiState.value = AgentUiState.Error(e.localizedMessage ?: "Critical engine initialization failure")
            }
        }
    }

    fun generateResponseForCurrentSession(prompt: String) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            try {
                // 1. Persist user message to Room DB
                chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "User", text = prompt))

                _uiState.value = AgentUiState.Generating
                
                // 2. Execute agent loop
                val result = agentManager.promptAgent(prompt) { token -> }

                // 3. Persist agent response/result to Room DB
                chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "Muse", text = result))
                _uiState.value = AgentUiState.Success(result)
            } catch (e: Throwable) {
                // Catch native errors, JNI issues, or coroutine exceptions and write them securely
                dumpErrorToStorage("generateResponseForCurrentSession", e)
                
                val errorMsg = e.localizedMessage ?: "Native execution error"
                chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "System", text = "Error: $errorMsg"))
                _uiState.value = AgentUiState.Error(errorMsg)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            agentManager.shutdown()
        } catch (e: Exception) {
            dumpErrorToStorage("onCleared_Shutdown", e)
        }
    }
}

sealed class AgentUiState {
    object Idle : AgentUiState()
    data class Loading(val message: String) : AgentUiState()
    object Ready : AgentUiState()
    object Generating : AgentUiState()
    data class Success(val response: String) : AgentUiState()
    data class Error(val message: String) : AgentUiState()
}
