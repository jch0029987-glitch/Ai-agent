package com.jeremy.ai.agent

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

class GitHubToolClient(private val token: String = "") {
    private val client = HttpClient(CIO)

    suspend fun getOpenIssues(owner: String, repo: String): String {
        return try {
            val response: HttpResponse = client.get("https://api.github.com/repos/$owner/$repo/issues") {
                if (token.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            response.bodyAsText()
        } catch (e: Exception) {
            "Error fetching GitHub issues: ${e.localizedMessage}"
        }
    }
}
