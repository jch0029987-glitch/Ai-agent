package com.jeremy.ai.agent

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.IOException

object LlamaServerManager {
    private const val TAG = "LlamaServerManager"
    private var serverProcess: Process? = null
    var lastErrorReason: String = ""
        private set

    private fun getOptimalThreadCount(context: Context): Int {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val thermalStatus = powerManager.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                Log.w(TAG, "Thermal elevation detected ($thermalStatus). Throttling threads to 3.")
                return 3
            }
        }
        // Tensor G4 prime (1x Cortex-X4) + 4x Cortex-A720 performance cores = 4 threads optimal
        return 4
    }

    fun startServer(context: Context, modelPathOrName: String): Boolean {
        if (serverProcess != null && serverProcess!!.isAlive) {
            Log.i(TAG, "Server is already running.")
            return true
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val serverBinary = File(nativeLibDir, "libllama-server.so")

        val modelFile = if (modelPathOrName.startsWith("/")) {
            File(modelPathOrName)
        } else {
            File(context.filesDir, modelPathOrName)
        }

        if (!modelFile.exists() || !serverBinary.exists()) {
            lastErrorReason = "Model or Server binary missing."
            return false
        }

        val threadCount = getOptimalThreadCount(context)

        try {
            val pb = ProcessBuilder(
                serverBinary.absolutePath,
                "--model", modelFile.absolutePath,
                "--ctx-size", "4096",
                "--threads", threadCount.toString(),
                "--threads-batch", threadCount.toString(),
                "--batch-size", "512",
                "--ubatch-size", "128",
                "--flash-attn", "on",       // FlashAttention for reduced KV cache overhead
                "--port", "8080",
                "--host", "0.0.0.0"
            )

            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeLibDir
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["OMP_NUM_THREADS"] = threadCount.toString()

            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)

            val process = pb.start()
            serverProcess = process

            Thread.sleep(600)

            if (!process.isAlive) {
                val reader = process.inputStream.bufferedReader()
                lastErrorReason = reader.readText().trim()
                serverProcess = null
                return false
            }

            Log.i(TAG, "Hardware-accelerated llama-server running ($threadCount threads, FlashAttn enabled).")
            return true
        } catch (e: IOException) {
            lastErrorReason = e.localizedMessage ?: "Process start exception"
            serverProcess = null
            return false
        }
    }

    fun stopServer() {
        serverProcess?.let {
            it.destroy()
            serverProcess = null
            Log.i(TAG, "Stopped llama-server process.")
        }
    }
}
