package com.jeremy.ai.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val agentManager = AgentManager(application)

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun initializeModelFromDownloads(fileName: String = "") {
        viewModelScope.launch {
            _uiState.value = AgentUiState.Loading("Connecting to Termux llama-server...")
            
            // Bypasses local file mounting; delegates directly to Termux HTTP endpoint check
            val success = agentManager.setupEngineFromStorage("")
            
            if (success) {
                _uiState.value = AgentUiState.Ready
            } else {
                _uiState.value = AgentUiState.Error("Could not connect to Termux llama-server at 127.0.0.1:8080")
            }
        }
    }

    fun generateResponse(prompt: String) {
        viewModelScope.launch {
            _uiState.value = AgentUiState.Generating
            val result = agentManager.promptAgent(prompt)
            _uiState.value = AgentUiState.Success(result)
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
