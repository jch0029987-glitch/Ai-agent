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
        Log.i(TAG, "Muse AI Node Gateway Service Started.")

        // Initialize MediaPipe engine background instance
        initializeMediaPipeEngine()
        
        // Start background autonomous monitoring loop
        startAutonomousBackgroundLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local AI Agent Active")
            .setContentText("Connected to native MediaPipe LLM engine.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        // Promote to Foreground Service immediately to avoid LMK termination
        startForeground(NOTIFICATION_ID, notification)

        val prompt = intent?.getStringExtra("EXTRA_PROMPT")
        if (!prompt.isNullOrBlank()) {
            serviceScope.launch {
                val result = agentManager.promptAgent(prompt)
                NotificationHelper.postMessage(
                    applicationContext,
                    "Agent Task Result",
                    result
                )
            }
        }

        return START_STICKY
    }

    private fun initializeMediaPipeEngine() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val success = agentManager.setupEngineFromStorage("model.bin")
                if (success) {
                    Log.i(TAG, "Background MediaPipe engine successfully initialized under AgentService.")
                } else {
                    Log.e(TAG, "Failed to initialize MediaPipe engine in background.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MediaPipe engine: ${e.localizedMessage}")
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
        agentManager.shutdown()
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

        Log.i(TAG, "Agent Gateway Service Destroyed.")
    }
}
