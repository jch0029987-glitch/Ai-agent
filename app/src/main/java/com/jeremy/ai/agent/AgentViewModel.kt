package com.jeremy.ai.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeremy.ai.agent.data.AppDatabase
import com.jeremy.ai.agent.data.ChatMessageEntity
import com.jeremy.ai.agent.data.ChatSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ChatMessageItem(val sender: String, val text: String)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessageItem> = emptyList()
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val agentManager = AgentManager(application)
    private val chatDao = AppDatabase.getDatabase(application).chatDao()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    var sessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    var currentSessionId by mutableStateOf<String?>(null)
        private set

    val currentSession: ChatSession?
        get() = sessions.find { it.id == currentSessionId }

    init {
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

    fun createNewSession(title: String = "Thread ${sessions.size + 1}") {
        viewModelScope.launch {
            val newEntity = ChatSessionEntity(title = title)
            chatDao.insertSession(newEntity)
            selectSession(newEntity.id)
        }
    }

    fun selectSession(sessionId: String) {
        currentSessionId = sessionId
        viewModelScope.launch {
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
        }
    }

    fun initializeModelFromDownloads(fileName: String = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm") {
        viewModelScope.launch {
            _uiState.value = AgentUiState.Loading("Connecting to local engine...")
            val result = agentManager.setupEngineFromStorage(fileName)
            
            _uiState.value = if (result.isSuccess) {
                AgentUiState.Ready
            } else {
                val exception = result.exceptionOrNull()
                AgentUiState.Error(exception?.localizedMessage ?: "Could not connect to engine backend.")
            }
        }
    }

    fun generateResponseForCurrentSession(prompt: String) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            // 1. Persist user message to Room DB
            chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "User", text = prompt))

            _uiState.value = AgentUiState.Generating
            try {
                // 2. Execute agent loop
                val result = agentManager.promptAgent(prompt) { token -> }

                // 3. Persist agent response/result to Room DB
                chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "Muse", text = result))
                _uiState.value = AgentUiState.Success(result)
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Unknown error"
                chatDao.insertMessage(ChatMessageEntity(sessionId = sessionId, sender = "System", text = "Error: $errorMsg"))
                _uiState.value = AgentUiState.Error(errorMsg)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        agentManager.shutdown()
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
