package com.jeremy.ai.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jeremy.ai.agent.AgentUiState
import com.jeremy.ai.agent.AgentViewModel

@Composable
fun AgentChatScreen(
    viewModel: AgentViewModel = viewModel(),
    requestBatteryExemption: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSession = viewModel.currentSession

    var modelPathInput by remember { mutableStateOf("Qwen2.5-Coder-1.5B-Instruct_int4.litertlm") }
    var promptInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Muse AI Agent (MediaPipe)", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = requestBatteryExemption) {
                Text("Battery Opt")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Model Path Input Row
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = modelPathInput,
                onValueChange = { modelPathInput = it },
                label = { Text("Model File Name / Path") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.initializeModelFromDownloads(modelPathInput)
                }
            ) {
                Text("Load")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // UI State / Status Banner
        val (bannerContainerColor, bannerContentColor, statusMessage) = when (uiState) {
            is AgentUiState.Idle -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Status: Ready to load model.")
            is AgentUiState.Loading -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, (uiState as AgentUiState.Loading).message)
            is AgentUiState.Ready -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "Status: Engine initialized and ready!")
            is AgentUiState.Generating -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Status: Generating response...")
            is AgentUiState.Success -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Status: Response complete.")
            is AgentUiState.Error -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Status Error: ${(uiState as AgentUiState.Error).message}")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = bannerContainerColor)
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(12.dp),
                color = bannerContentColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Chat Message Thread from Current Session
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(currentSession?.messages ?: emptyList()) { msg ->
                Text(
                    text = "${msg.sender}: ${msg.text}",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Prompt Execution Box
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Ask Muse...") },
                modifier = Modifier.weight(1f),
                enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (promptInput.isNotBlank()) {
                        val userMsg = promptInput
                        promptInput = ""
                        viewModel.generateResponseForCurrentSession(userMsg)
                    }
                },
                enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
            ) {
                Text("Send")
            }
        }
    }
}
