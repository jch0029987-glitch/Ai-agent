package com.jeremy.ai.agent

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ErrorLogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rawLogContent by remember { mutableStateOf("Loading logs...") }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Load log file with internal storage priority and external storage fallback
    LaunchedEffect(Unit) {
        val internalFile = File(context.filesDir, "agent_error_log.txt")
        val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val externalFile = File(externalDir, "agent_error_log.txt")

        rawLogContent = when {
            internalFile.exists() -> {
                internalFile.readText()
            }
            externalFile.exists() -> {
                externalFile.readText()
            }
            else -> {
                "No error logs found.\nChecked internal: ${internalFile.absolutePath}\nChecked external: ${externalFile.absolutePath}"
            }
        }
        
        if (rawLogContent.isNotBlank() && !rawLogContent.startsWith("No error logs")) {
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
                    // Clear both potential locations to be thorough
                    val internalFile = File(context.filesDir, "agent_error_log.txt")
                    if (internalFile.exists()) internalFile.delete()
                    
                    val externalFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "agent_error_log.txt")
                    if (externalFile.exists()) externalFile.delete()

                    rawLogContent = "Logs cleared from all locations."
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
