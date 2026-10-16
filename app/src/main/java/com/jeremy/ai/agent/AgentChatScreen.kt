package com.jeremy.ai.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AgentViewModel = viewModel(),
    requestBatteryExemption: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var modelFileName by remember { mutableStateOf("model.bin") } // Updated default to MediaPipe bundle format
    var inputQuery by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val currentSession = viewModel.currentSession

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Chat Center",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Divider()

                // List of active chat threads
                viewModel.sessions.forEach { session ->
                    NavigationDrawerItem(
                        label = { Text(session.title) },
                        selected = session.id == viewModel.currentSessionId,
                        onClick = {
                            viewModel.selectSession(session.id)
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // New Thread Button
                Button(
                    onClick = {
                        viewModel.createNewSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("+ New Thread")
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentSession?.title ?: "Chat") }, // Fixed nullable receiver
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Text("≡", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Section: Model Loading & Status
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Muse AI Agent (MediaPipe)", // Updated for hardware acceleration engine
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
                            label = { Text("Model File Path / Name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
                        )
                        Button(
                            onClick = {
                                requestBatteryExemption()
                                viewModel.initializeModelFromDownloads(modelFileName)
                            },
                            enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
                        ) {
                            Text("Load")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Middle Section: Session Message History Log
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusBanner = when (uiState) {
                            is AgentUiState.Ready -> "Engine ready. Enter a command below."
                            is AgentUiState.Generating -> "Thinking and executing tools..."
                            is AgentUiState.Loading -> (uiState as AgentUiState.Loading).message
                            is AgentUiState.Error -> "Error: ${(uiState as AgentUiState.Error).message}"
                            else -> "Model offline."
                        }

                        Text(
                            text = "Status: $statusBanner",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Divider(modifier = Modifier.padding(vertical = 4.dp))

                        val messages = currentSession?.messages ?: emptyList()
                        if (messages.isEmpty()) {
                            Text(
                                text = "Thread initialized. Type a prompt or task below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            messages.forEach { msg ->
                                Text(
                                    text = "${msg.sender}: ${msg.text}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                        enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating
                    )

                    Button(
                        onClick = {
                            if (inputQuery.isNotBlank()) {
                                val query = inputQuery
                                inputQuery = ""
                                viewModel.generateResponseForCurrentSession(query)
                            }
                        },
                        enabled = (uiState !is AgentUiState.Loading && uiState !is AgentUiState.Generating) && inputQuery.isNotBlank()
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}
