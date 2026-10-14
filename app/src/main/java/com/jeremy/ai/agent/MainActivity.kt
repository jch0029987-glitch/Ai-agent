package com.jeremy.ai.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Some agent tools require permissions to execute fully.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupGlobalCrashHandler()
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AgentDashboard(
                        requestBatteryExemption = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            val errorMsg = if (stackTrace.length > 500) stackTrace.substring(0, 500) + "..." else stackTrace

            NotificationHelper.postError(
                applicationContext,
                "Fatal Crash (${thread.name})",
                errorMsg
            )

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions)
        }
    }
}

@Composable
fun AgentDashboard(
    requestBatteryExemption: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val agentManager = remember { AgentManager(context) }
    val coroutineScope = rememberCoroutineScope()

    var promptText by remember { mutableStateOf("") }
    var modelFileName by remember { mutableStateOf("model.gguf") }
    var outputLog by remember { mutableStateOf("Engine ready. Configure model path and tap 'Start Engine'.\n") }
    var isModelLoaded by remember { mutableStateOf(false) }
    var isCliMode by remember { mutableStateOf(false) }
    var isMatrixBridgeActive by remember { mutableStateOf(false) }

    val matrixBridge = remember {
        MatrixAgentBridge(
            context = context,
            agentManager = agentManager,
            homeserverUrl = "http://127.0.0.1:8082",
            accessToken = BuildConfig.MATRIX_ACCESS_TOKEN,
            botUserId = "@ai:localhost"
        )
    }

    LaunchedEffect(isCliMode) {
        agentManager.useCliMode = isCliMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Local AI Agent (Pixel 9)",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    containerColor = if (isModelLoaded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isModelLoaded) "Status: Native Engine Online" else "Status: Engine Offline",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = outputLog,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isCliMode) "Mode: Direct CLI Process" else "Mode: Background Server (HTTP)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = isCliMode,
                    onCheckedChange = { isCliMode = it }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isMatrixBridgeActive) "Matrix Bridge: Connected (@ai:localhost)" else "Matrix Bridge: Offline",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = isMatrixBridgeActive,
                    onCheckedChange = { active ->
                        isMatrixBridgeActive = active
                        if (active) {
                            matrixBridge.startListening()
                            outputLog += "-> Matrix Agent Bridge started syncing on port 8082...\n"
                        } else {
                            matrixBridge.stopListening()
                            outputLog += "-> Matrix Agent Bridge stopped.\n"
                        }
                    },
                    enabled = isModelLoaded
                )
            }

            OutlinedTextField(
                value = modelFileName,
                onValueChange = { modelFileName = it },
                label = { Text("Model File Name (e.g. model.gguf)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("Ask local agent or assign task...") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        requestBatteryExemption()
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                withContext(Dispatchers.Main) {
                                    outputLog += "-> Locating model file...\n"
                                }

                                val externalFile = File("/sdcard/Download/models/$modelFileName")
                                val internalFile = File(context.filesDir, modelFileName)

                                val targetPath = if (externalFile.exists()) {
                                    withContext(Dispatchers.Main) {
                                        outputLog += "-> Copying fresh model from external storage to internal app directory...\n"
                                    }
                                    if (internalFile.exists()) {
                                        internalFile.delete()
                                    }
                                    externalFile.inputStream().use { input ->
                                        internalFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        outputLog += "-> Copy complete: ${internalFile.absolutePath}\n"
                                    }
                                    internalFile.absolutePath
                                } else if (internalFile.exists() && internalFile.length() > 0) {
                                    withContext(Dispatchers.Main) {
                                        outputLog += "-> Using existing internal model: ${internalFile.absolutePath}\n"
                                    }
                                    internalFile.absolutePath
                                } else {
                                    withContext(Dispatchers.Main) {
                                        outputLog += "-> External model not found. Defaulting to internal path.\n"
                                    }
                                    modelFileName
                                }

                                val success = agentManager.setupEngineFromStorage(targetPath)

                                withContext(Dispatchers.Main) {
                                    isModelLoaded = success
                                    if (success) {
                                        outputLog += "-> Native engine initialized successfully.\n"
                                        val serviceIntent = Intent(context, AgentService::class.java)
                                        ContextCompat.startForegroundService(context, serviceIntent)
                                        outputLog += "-> Protected AgentService active.\n"
                                    } else {
                                        val exactReason = LlamaServerManager.lastErrorReason.ifBlank { "Unknown native failure" }
                                        outputLog += "-> Failed: $exactReason\n"
                                        NotificationHelper.postError(context, "Engine Launch Failed", exactReason)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    val errorMsg = e.localizedMessage ?: "Unknown initialization exception"
                                    outputLog += "-> Error: $errorMsg\n"
                                    NotificationHelper.postError(context, "Agent Initialization Exception", errorMsg)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Engine")
                }

                Button(
                    onClick = {
                        if (promptText.isNotBlank()) {
                            val currentPrompt = promptText
                            outputLog += "User: $currentPrompt\nAgent: "
                            promptText = ""
                            coroutineScope.launch {
                                try {
                                    agentManager.promptAgent(currentPrompt) { token ->
                                        outputLog += token
                                    }
                                    outputLog += "\n\n"
                                } catch (e: Exception) {
                                    val errorMsg = e.localizedMessage ?: "Inference error"
                                    outputLog += "\n-> Inference Error: $errorMsg\n"
                                    NotificationHelper.postError(context, "Agent Inference Failure", errorMsg)
                                }
                            }
                        }
                    },
                    enabled = isModelLoaded,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Run Agent")
                }
            }
        }
    }
}
