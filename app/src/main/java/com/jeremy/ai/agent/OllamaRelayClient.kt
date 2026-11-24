package com.jeremy.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

suspend fun fetchOllamaModels(baseUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val conn = (URL("$cleanUrl/api/tags").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 4_000
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            error("Ollama HTTP $code: $body")
        }

        val modelsArray = JSONObject(body).optJSONArray("models")
        buildList {
            if (modelsArray != null) {
                for (i in 0 until modelsArray.length()) {
                    val name = modelsArray.optJSONObject(i)?.optString("name").orEmpty().trim()
                    if (name.isNotEmpty() && name !in this) {
                        add(name)
                    }
                }
            }
        }
    }
}

suspend fun runVisionThenCoder(
    baseUrl: String,
    visionModel: String?,
    coderModel: String,
    systemInstruction: String,
    userPrompt: String,
    imageBase64: String? = null
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val activeCoder = coderModel.trim().ifBlank { "qwen2.5-coder:3b" }
        val activeVision = visionModel?.trim()?.takeIf { it.isNotEmpty() }

        val finalPrompt = if (!imageBase64.isNullOrBlank() && activeVision != null) {
            val visualSummary = postOllamaChat(
                baseUrl = cleanUrl,
                model = activeVision,
                systemPrompt = "Extract all UI text, error messages, code details, and visual layout facts accurately.",
                userPrompt = userPrompt,
                imageBase64 = imageBase64,
                keepAlive = 0 // Unload vision model immediately after this pass
            )
            """
            Visual summary ($activeVision):
            $visualSummary

            Task:
            $userPrompt
            """.trimIndent()
        } else {
            userPrompt
        }

        postOllamaChat(
            baseUrl = cleanUrl,
            model = activeCoder,
            systemPrompt = systemInstruction,
            userPrompt = finalPrompt,
            imageBase64 = null,
            keepAlive = "10m"
        )
    }
}

private fun postOllamaChat(
    baseUrl: String,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    keepAlive: Any
): String {
    val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
            if (!imageBase64.isNullOrBlank()) {
                put("images", JSONArray().put(imageBase64))
            }
        })
    }

    val payload = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("stream", false)
        put("keep_alive", keepAlive)
    }

    val conn = (URL("$baseUrl/api/chat").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
        connectTimeout = 10_000
        readTimeout = 120_000
    }

    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

    val code = conn.responseCode
    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()

    if (code !in 200..299) {
        error("Ollama HTTP $code: $body")
    }

    return JSONObject(body)
        .optJSONObject("message")
        ?.optString("content")
        .orEmpty()
        .trim()
}
