package com.jeremy.ai.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackendStartupDialog(
    initialSettings: AiBackendSettings,
    onConfirm: (AiBackendSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedMode by remember(initialSettings) { mutableStateOf(initialSettings.mode) }
    var ollamaUrl by remember(initialSettings) { mutableStateOf(initialSettings.ollamaBaseUrl) }
    var coderModel by remember(initialSettings) { mutableStateOf(initialSettings.ollamaCoderModel) }
    var visionModel by remember(initialSettings) { mutableStateOf(initialSettings.ollamaVisionModel.orEmpty()) }
    var showOnStartup by remember(initialSettings) { mutableStateOf(initialSettings.showOnStartup) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    suspend fun refreshModels() {
        isLoadingModels = true
        modelFetchError = null
        fetchOllamaModels(ollamaUrl)
            .onSuccess { models ->
                availableModels = models
                if (models.isNotEmpty()) {
                    if (coderModel !in models) {
                        coderModel = models.firstOrNull { it.contains("coder", ignoreCase = true) }
                            ?: models.firstOrNull { !it.contains("minicpm-v", ignoreCase = true) }
                            ?: models.first()
                    }
                    if (visionModel.isNotBlank() && visionModel !in models) {
                        visionModel = models.firstOrNull {
                            it.contains("minicpm-v", ignoreCase = true) || it.contains("vision", ignoreCase = true)
                        }.orEmpty()
                    }
                }
            }
            .onFailure { err ->
                modelFetchError = err.message ?: "Unable to reach Ollama /api/tags"
            }
        isLoadingModels = false
    }

    LaunchedEffect(selectedMode, ollamaUrl) {
        if (selectedMode == AiBackendMode.OLLAMA) {
            refreshModels()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select AI Backend") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AiBackendMode.entries.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { selectedMode = mode },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = mode.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (mode == AiBackendMode.LITERT_LM) {
                                    "On-device LiteRT-LM engine"
                                } else {
                                    "Termux Ollama server (vision -> coder relay)"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (selectedMode == AiBackendMode.OLLAMA) {
                    OutlinedTextField(
                        value = ollamaUrl,
                        onValueChange = { ollamaUrl = it },
                        label = { Text("Ollama Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Termux Models", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { scope.launch { refreshModels() } }) {
                            Text(if (isLoadingModels) "Loading..." else "Refresh")
                        }
                    }

                    if (availableModels.isNotEmpty()) {
                        Text("Coder Model", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            availableModels.forEach { modelName ->
                                FilterChip(
                                    selected = coderModel == modelName,
                                    onClick = { coderModel = modelName },
                                    label = { Text(modelName) }
                                )
                            }
                        }

                        Text("Vision Model (optional, unloads after pass)", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = visionModel.isBlank(),
                                onClick = { visionModel = "" },
                                label = { Text("None") }
                            )
                            availableModels.forEach { modelName ->
                                FilterChip(
                                    selected = visionModel == modelName,
                                    onClick = { visionModel = modelName },
                                    label = { Text(modelName) }
                                )
                            }
                        }
                    }

                    if (modelFetchError != null) {
                        Text(
                            text = "List unavailable: $modelFetchError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    OutlinedTextField(
                        value = coderModel,
                        onValueChange = { coderModel = it },
                        label = { Text("Coder Model (e.g. qwen2.5-coder:3b)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = visionModel,
                        onValueChange = { visionModel = it },
                        label = { Text("Vision Model (e.g. minicpm-v:latest)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showOnStartup,
                        onCheckedChange = { showOnStartup = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Prompt on startup",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        AiBackendSettings(
                            mode = selectedMode,
                            ollamaBaseUrl = ollamaUrl,
                            ollamaCoderModel = coderModel,
                            ollamaVisionModel = visionModel.trim().ifBlank { null },
                            showOnStartup = showOnStartup
                        )
                    )
                }
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Use Saved")
            }
        }
    )
}
