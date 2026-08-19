package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ApproveResponse
import com.jauschua.ironlogv2.data.api.dto.GenerateRequest
import com.jauschua.ironlogv2.data.api.dto.GenerateResponse
import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import com.jauschua.ironlogv2.data.api.dto.SessionSummary
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GenerateRepo(private val apiClient: ApiClient) {

    // /generate can invoke a live Gemini call bounded server-side at 60s
    // (ironlog/generation/gemini.py's httpx.Client timeout) with up to 3
    // server-side retries before it degrades to a deterministic fallback --
    // true worst case is 3x60s=180s. ApiClient's global 10s timeout (fine for
    // every other endpoint) was firing mid-generation and reading to the
    // athlete as "can't generate, times out" (2026-08-19). 200s gives a
    // margin above that 180s worst case. Per-request override only for this
    // call.
    suspend fun generate(dayRole: String): Result<GenerateResponse> = runCatchingApi {
        apiClient.http.post("/generate") {
            contentType(ContentType.Application.Json)
            setBody(GenerateRequest(dayRole))
            timeout {
                requestTimeoutMillis = 200_000
                socketTimeoutMillis = 200_000
            }
        }.body()
    }

    suspend fun approve(candidateId: String): Result<ApproveResponse> = runCatchingApi {
        apiClient.http.post("/sessions/$candidateId/approve") {
            contentType(ContentType.Application.Json)
        }.body()
    }

    suspend fun programDays(programId: Int): Result<List<String>> = runCatchingApi {
        apiClient.http.get("/programs/$programId/days").body()
    }

    suspend fun pastSessions(): Result<List<SessionSummary>> = runCatchingApi {
        apiClient.http.get("/sessions").body()
    }

    suspend fun sessionLogs(id: Int): Result<LoggedSetsResponse> = runCatchingApi {
        apiClient.http.get("/sessions/$id/logs").body()
    }
}
