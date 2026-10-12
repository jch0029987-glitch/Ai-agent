package com.jeremy.ai.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AgentChatScreen(
    viewModel: AgentViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var modelFileName by remember { mutableStateOf("model.gguf") }
    var inputQuery by remember { mutableStateOf("") }

    // Determine what text to display in the output box based on the UI state
    val currentOutput = when (val state = uiState) {
        is AgentUiState.Ready -> "Engine ready. Enter a command below."
        is AgentUiState.Generating -> "Thinking and executing tools..."
        is AgentUiState.Success -> state.response
        is AgentUiState.Error -> "Error: ${state.message}"
        is AgentUiState.Loading -> state.message
        else -> "Model offline."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Section: Model Loading & Status
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Muse AI Agent (Vulkan)",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = modelFileName,
                    onValueChange = { modelFileName = it },
                    label = { Text("Model File in Download/models") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
                )
                Button(
                    onClick = { viewModel.initializeModelFromDownloads(modelFileName) },
                    enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
                ) {
                    Text("Load")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Middle Section: Chat Output Display with Scroll
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = currentOutput,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Section: Prompt Input & Send
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = inputQuery,
                onValueChange = { inputQuery = it },
                label = { Text("Ask Muse...") },
                modifier = Modifier.weight(1f),
                enabled = uiState is AgentUiState.Ready || uiState is AgentUiState.Success || uiState is AgentUiState.Error
            )

            Button(
                onClick = {
                    if (inputQuery.isNotBlank()) {
                        viewModel.generateResponse(inputQuery)
                        inputQuery = ""
                    }
                },
                enabled = (uiState is AgentUiState.Ready || uiState is AgentUiState.Success || uiState is AgentUiState.Error) && inputQuery.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}
