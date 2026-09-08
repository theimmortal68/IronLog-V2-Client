package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ApproveResponse
import com.jauschua.ironlogv2.data.api.dto.GenerateRequest
import com.jauschua.ironlogv2.data.api.dto.GenerateResponse
import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import com.jauschua.ironlogv2.data.api.dto.SessionSummary
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.job

class GenerateRepo(private val apiClient: ApiClient) {

    // Gemini can take 3x60s before falling back. Give /generate its own client
    // defaults so its 200s budget does not depend on a per-request override.
    // Ktor 3.0.3's OkHttp engine applies these socket timeouts to read/write;
    // HttpTimeout enforces the total request budget. Keep the 5s connect limit.
    // A prior per-request `timeout {}` override on this same call (2026-08-19)
    // did not reliably take effect against the OkHttp engine and the "Request
    // timed out" symptom recurred (2026-09-08) -- do not revert to that pattern.
    private val generateHttp by lazy {
        apiClient.http.config {
            install(HttpTimeout) {
                requestTimeoutMillis = 200_000
                socketTimeoutMillis = 200_000
            }
        }.also { client ->
            // config() shares the parent's engine (manageEngine is inherited too),
            // so closing this derived client independently would close the shared
            // engine for every other repo. Never call generateHttp.close() directly --
            // only close it via the parent's own completion below.
            apiClient.http.coroutineContext.job.invokeOnCompletion { client.close() }
        }
    }

    suspend fun generate(dayRole: String): Result<GenerateResponse> = runCatchingApi {
        generateHttp.post("/generate") {
            contentType(ContentType.Application.Json)
            setBody(GenerateRequest(dayRole))
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
