package com.jeremy.ai.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.jeremy.ai.backend.configureRouting
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

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
        // Enforce Android 16 mandatory edge-to-edge layout display
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        setupGlobalCrashHandler()
        checkAndRequestPermissions()
        startEmbeddedServer()

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

    private fun startEmbeddedServer() {
        try {
            if (ktorServer == null) {
                ktorServer = embeddedServer(Netty, port = 8080) {
                    configureRouting()
                }.start(wait = false)
                Log.i("MainActivity", "Ktor Netty server successfully started on port 8080")
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
