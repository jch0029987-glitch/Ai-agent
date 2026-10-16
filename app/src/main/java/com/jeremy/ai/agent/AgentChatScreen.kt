package com.jeremy.ai.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jeremy.ai.agent.AgentManager
import kotlinx.coroutines.launch

@Composable
fun AgentChatScreen(agentManager: AgentManager) {
    val scope = rememberCoroutineScope()
    var modelPathInput by remember { mutableStateOf("Qwen2.5-Coder-1.5B-Instruct_int4.litertlm") }
    var statusText by remember { mutableStateOf("Status: Ready to load model.") }
    var isError by remember { mutableStateOf(false) }
    var promptInput by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<String>() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Muse AI Agent (MediaPipe)", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        // Model Path Input Row
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = modelPathInput,
                onValueChange = { modelPathInput = it },
                label = { Text("Model File Path / Name") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        statusText = "Status: Initializing engine..."
                        isError = false
                        val result = agentManager.setupEngineFromStorage(modelPathInput)
                        
                        if (result.isSuccess) {
                            statusText = "Status: Engine initialized and ready!"
                        } else {
                            isError = true
                            val exception = result.exceptionOrNull()
                            statusText = "Status Error: ${exception?.javaClass?.simpleName ?: "Exception"}\n${exception?.localizedMessage ?: "Unknown initialization error."}"
                        }
                    }
                }
            ) {
                Text("Load")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status / Error Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Chat Message Thread
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { msg ->
                Text(text = msg, modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Prompt Execution Box
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Ask Muse...") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val userMsg = promptInput
                    if (userMsg.isNotBlank()) {
                        messages.add("User: $userMsg")
                        promptInput = ""
                        scope.launch {
                            val response = agentManager.promptAgent(userMsg)
                            messages.add("Agent: $response")
                        }
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}
