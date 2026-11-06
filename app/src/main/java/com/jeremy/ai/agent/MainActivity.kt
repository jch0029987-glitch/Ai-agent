package com.jeremy.ai.agent

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jeremy.ai.agent.ui.AgentChatScreen
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import com.jeremy.ai.agent.ShellSessionManager
import com.jeremy.ai.agent.configureRouting

class MainActivity : ComponentActivity() {

    private var ktorServer: EmbeddedServer<*, *>? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        "com.termux.permission.RUN_COMMAND"
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
        // Enforce Android mandatory edge-to-edge layout display
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        setupGlobalCrashHandler()
        checkAndRequestPermissions()
        checkAndRequestExactAlarmPermission()

        // Bind application context for Termux Intent shell bridge
        ShellSessionManager.appContext = applicationContext

        startEmbeddedServer()
        startTelegramBridge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(), // Protects content from overlapping system bars & cutouts
                    color = MaterialTheme.colorScheme.background
                ) {
                    AgentChatScreen(
                        requestBatteryExemption = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    private fun startTelegramBridge() {
        try {
            val intent = Intent(this, TelegramBridgeService::class.java)
            startService(intent)
            Log.i("MainActivity", "TelegramBridgeService successfully started.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start TelegramBridgeService", e)
            Toast.makeText(this, "Telegram Bridge failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startEmbeddedServer() {
        try {
            if (ktorServer == null) {
                ktorServer = embeddedServer(Netty, port = 8080) {
                    // 1. Install WebSockets plugin for real-time token streaming routes
                    install(WebSockets) {
                        pingPeriod = 15.seconds
                        timeout = 15.seconds
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    // 2. Install Server-Sent Events (SSE) plugin required for official MCP transport
                    install(SSE)

                    // 3. Install ContentNegotiation to parse JSON-RPC payloads (fixes 406 Not Acceptable)
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            prettyPrint = true
                        })
                    }

                    // 4. Register application routing endpoints (including /mcp)
                    configureRouting()
                }.start(wait = false)
                Log.i("MainActivity", "Ktor Netty server successfully started on port 8080 with MCP & SSE support")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start embedded Ktor server", e)
            Toast.makeText(this, "Server start error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ktorServer?.stop(1000, 2000)
            ktorServer = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping Ktor server", e)
        }
    }

    private fun checkAndRequestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to request exact alarm permission", e)
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
