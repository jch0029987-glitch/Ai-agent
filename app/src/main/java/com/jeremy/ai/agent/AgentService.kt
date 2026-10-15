package com.jeremy.ai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jeremy.ai.agent.services.AgentNotificationListener
import kotlinx.coroutines.*

class AgentService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var agentManager: AgentManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "AgentServiceChannel"
        const val NOTIFICATION_ID = 1337
        private const val TAG = "AgentGatewayService"
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        agentManager = AgentManager(applicationContext)
        createNotificationChannel()

        // Acquire a partial wake lock to keep the CPU running background tasks
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LocalAIAgent::ServiceWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        isRunning = true
        Log.i(TAG, "OpenClaw/Muse Node Gateway Service Started.")

        // 1. Ensure native llama-server starts up / stays alive inside the service
        ensureServerRunning()
        
        // 2. Start background autonomous monitoring loop
        startAutonomousBackgroundLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local AI Agent Active")
            .setContentText("Connected to native llama-server gateway.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        // Promote to Foreground Service immediately to avoid LMK termination
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun ensureServerRunning() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Pass the default internal model path or fallback file name
                val started = LlamaServerManager.startServer(applicationContext, "model.gguf")
                if (started) {
                    Log.i(TAG, "Background llama-server verified and running under AgentService.")
                } else {
                    Log.e(TAG, "Failed to start server in background: ${LlamaServerManager.lastErrorReason}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ensuring background server state: ${e.localizedMessage}")
            }
        }
    }

    private fun startAutonomousBackgroundLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    // Proactive background monitor for incoming notifications
                    val notifications = AgentNotificationListener.getContextSummary()
                    if (notifications.isNotBlank() && notifications != "No active notifications.") {
                        Log.i(TAG, "Proactive trigger detected from notifications. Ready for event dispatch.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background loop: ${e.localizedMessage}")
                }
                // Poll interval for background triggers (every 30 seconds)
                delay(30000) 
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Prevents Android from killing the local AI agent gateway."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceJob.cancel()

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            // Ignore release exceptions
        }

        // Clean up native server process when service terminates
        LlamaServerManager.stopServer()
        Log.i(TAG, "Agent Gateway Service Destroyed.")
    }
}
