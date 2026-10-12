package com.jeremy.ai.agent

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlamaBridge(private val context: Context) {
    private val TAG = "LlamaBridge"
    private val serverUrl = "http://127.0.0.1:8080/completion"
    private val healthUrl = "http://127.0.0.1:8080/health"
    private var isConnected = false

    private fun getOptimalThreadCount(): Int {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val thermalStatus = powerManager.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                Log.w(TAG, "Thermal status elevated ($thermalStatus). Throttling to 3 threads.")
                return 3
            }
        }
        // Tensor G4 optimal balance: 1x Cortex-X4 prime + 4x Cortex-A720 performance cores
        return 4
    }

    suspend fun initializeModel(modelFileName: String): Boolean = withContext(Dispatchers.IO) {
        val started = LlamaServerManager.startServer(context, modelFileName)
        if (!started) {
            val reason = LlamaServerManager.lastErrorReason.ifBlank { "Unknown startup error" }
            Log.e(TAG, "Failed to start embedded llama-server process: $reason")
            return@withContext false
        }

        var attempts = 0
        while (attempts < 15) {
            try {
                val url = URL(healthUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1000
                    readTimeout = 1000
                }
                
                if (connection.responseCode == 200) {
                    isConnected = true
                    Log.i(TAG, "Embedded llama-server is active and healthy.")
                    return@withContext true
                }
            } catch (e: Exception) {
                // Server still booting / weights loading
            }
            attempts++
            Thread.sleep(500)
        }

        Log.e(TAG, "Timed out waiting for embedded llama-server health check.")
        isConnected = false
        return@withContext false
    }

    fun releaseModel() {
        LlamaServerManager.stopServer()
        isConnected = false
    }

    suspend fun generate(prompt: String, onToken: (String) -> Unit = {}): String = 
        generateWithServer(prompt, "", onToken)

    suspend fun generateWithGrammar(prompt: String, grammar: String, onToken: (String) -> Unit = {}): String =
        generateWithServer(prompt, grammar, onToken)

    private suspend fun generateWithServer(prompt: String, grammar: String, onToken: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext "Error: Local server is offline or uninitialized."
        }

        val fullResponse = StringBuilder()

        try {
            val url = URL(serverUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; utf-8")
                setRequestProperty("Accept", "text/event-stream")
                doOutput = true
                connectTimeout = 3000
                readTimeout = 60000
            }

            val stopTokens = JSONArray().apply {
                put("<|im_end|>")
                put("<|im_start|>")
                put("<|endoftext|>")
                put("\nUser:")
                put("\nAgent:")
                put("\n\nUser:")
            }

            val jsonBody = JSONObject().apply {
                put("prompt", prompt)
                put("n_predict", 128)
                put("temperature", 0.2)
                put("top_p", 0.95)
                put("top_k", 40)
                put("repeat_penalty", 1.15)
                put("cache_prompt", true)
                put("stream", true)
                put("stop", stopTokens)
                if (grammar.isNotBlank()) {
                    put("grammar", grammar)
                }
            }

            connection.outputStream.use { os ->
                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("data: ")) {
                            val jsonData = currentLine.substring(6).trim()
                            if (jsonData.isNotEmpty()) {
                                val json = JSONObject(jsonData)
                                val token = json.optString("content", "")
                                if (token.isNotEmpty()) {
                                    fullResponse.append(token)
                                    onToken(token)
                                }
                            }
                        }
                    }
                }
                return@withContext fullResponse.toString().trim()
            } else {
                return@withContext "Error: Local server returned HTTP $responseCode"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server inference request failed: ${e.localizedMessage}")
            return@withContext "Error: Connection exception - ${e.localizedMessage}"
        }
    }

    suspend fun generateWithCli(prompt: String, modelFileName: String = "model.gguf", grammar: String = "", onToken: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val cliBinary = File(nativeLibDir, "libllama-cli.so")
        
        if (!cliBinary.exists()) {
            return@withContext "Error: llama-cli binary (libllama-cli.so) not found in nativeLibraryDir."
        }

        val modelFile = if (modelFileName.startsWith("/")) File(modelFileName) else File(context.filesDir, modelFileName)
        if (!modelFile.exists()) {
            return@withContext "Error: Model file not found at ${modelFile.absolutePath}"
        }

        val threadCount = getOptimalThreadCount()

        val grammarFile = if (grammar.isNotBlank()) {
            val file = File(context.cacheDir, "cli_temp_grammar.gbnf")
            file.writeText(grammar)
            file
        } else null

        val command = mutableListOf(
            cliBinary.absolutePath,
            "-m", modelFile.absolutePath,
            "-p", prompt,
            "-n", "128",
            "-t", threadCount.toString(),
            "--temp", "0.2",
            "--repeat-penalty", "1.15",
            "-r", "<|im_end|>",
            "-r", "<|im_start|>",
            "--no-display-prompt"
        )

        if (grammarFile != null) {
            command.add("--grammar-file")
            command.add(grammarFile.absolutePath)
        }

        val fullResponse = StringBuilder()

        try {
            val pb = ProcessBuilder(command).apply {
                directory(context.filesDir)
                redirectErrorStream(true)
            }

            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeLibDir
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["OMP_NUM_THREADS"] = threadCount.toString()

            val process = pb.start()
            
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val token = line ?: continue
                    fullResponse.append(token).append("\n")
                    onToken(token)
                }
            }

            process.waitFor()
            grammarFile?.delete()

            return@withContext fullResponse.toString().trim()
        } catch (e: IOException) {
            Log.e(TAG, "CLI execution failed: ${e.localizedMessage}", e)
            return@withContext "Error: CLI exception - ${e.localizedMessage}"
        }
    }
}
