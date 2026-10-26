package com.jeremy.ai.agent.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jeremy.ai.agent.AgentUiState
import com.jeremy.ai.agent.AgentViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AgentChatScreen(
    viewModel: AgentViewModel = viewModel(),
    requestBatteryExemption: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSession = viewModel.currentSession

    // Updated to match the fixed sequence-length MediaPipe .task file name
    var modelPathInput by remember { mutableStateOf("qwen.task") }
    var promptInput by remember { mutableStateOf("") }
    
    // State to toggle the built-in Error Log Viewer dialog
    var showLogDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AI Agent (MediaPipe)", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(2.dp))
                // Live Ktor Server Status Indicator Badge
                ServerStatusBadge(isOnline = viewModel.isServerRunning)
            }
            
            // Action controls row (Error Logs + Battery Opt)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showLogDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "View Error Logs",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Button(onClick = requestBatteryExemption) {
                    Text("Battery Opt")
                }
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

    // Error Log Viewer Dialog Overlay
    if (showLogDialog) {
        ErrorLogViewerDialog(onDismiss = { showLogDialog = false })
    }
}

@Composable
fun ServerStatusBadge(isOnline: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336),
                    shape = CircleShape
                )
        )
        Text(
            text = if (isOnline) "Server: Online (Port 8080)" else "Server: Offline",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ErrorLogViewerDialog(onDismiss: () -> Unit) {
    var rawLogContent by remember { mutableStateOf("Loading logs...") }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val logFile = File(downloadsDir, "agent_error_log.txt")
        rawLogContent = if (logFile.exists()) {
            logFile.readText()
        } else {
            "No error log found at ${logFile.absolutePath}"
        }
        
        if (rawLogContent.isNotBlank()) {
            val lineCount = rawLogContent.lines().size
            if (lineCount > 0) {
                listState.scrollToItem(lineCount - 1)
            }
        }
    }

    val filteredLines = remember(rawLogContent, searchQuery) {
        val lines = rawLogContent.lines()
        if (searchQuery.isBlank()) {
            lines
        } else {
            lines.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent Error Logs") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text("Filter logs (e.g. promptAgent, SIGSEGV)...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                coroutineScope.launch {
                    if (filteredLines.isNotEmpty()) {
                        listState.animateScrollToItem(filteredLines.size - 1)
                    }
                }
            }) {
                Text("Bottom")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val logFile = File(downloadsDir, "agent_error_log.txt")
                    if (logFile.exists()) logFile.delete()
                    rawLogContent = "Log cleared."
                    searchQuery = ""
                }) {
                    Text("Clear")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
