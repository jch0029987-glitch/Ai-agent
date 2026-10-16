package com.jeremy.ai.agent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var agentManager: AgentManager

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }

    override fun onCreate() {
        super.onCreate()
        agentManager = AgentManager(this)
        Log.i(TAG, "AgentService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm"

        serviceScope.launch {
            Log.i(TAG, "Initializing MediaPipe engine from background service with model: $modelPath")
            val result = agentManager.setupEngineFromStorage(modelPath)
            
            if (result.isSuccess) {
                Log.i(TAG, "MediaPipe engine initialized successfully in background service.")
            } else {
                val exception = result.exceptionOrNull()
                Log.e(TAG, "Failed to initialize MediaPipe engine in service: ${exception?.localizedMessage}", exception)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        agentManager.shutdown()
        Log.i(TAG, "AgentService destroyed.")
    }
}
