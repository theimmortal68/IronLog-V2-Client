package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ApproveResponse
import com.jauschua.ironlogv2.data.api.dto.GenerateRequest
import com.jauschua.ironlogv2.data.api.dto.GenerateResponse
import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import com.jauschua.ironlogv2.data.api.dto.SessionSummary
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GenerateRepo(private val apiClient: ApiClient) {

    suspend fun generate(dayRole: String): Result<GenerateResponse> = runCatchingApi {
        apiClient.http.post("/generate") {
            contentType(ContentType.Application.Json)
            setBody(GenerateRequest(dayRole))
        }.body()
    }

    suspend fun approve(candidateId: String): Result<ApproveResponse> = runCatchingApi {
        apiClient.http.post("/sessions/$candidateId/approve") {
            contentType(ContentType.Application.Json)
        }.body()
    }

    suspend fun history(): Result<List<SessionSummary>> = runCatchingApi {
        apiClient.http.get("/sessions").body()
    }

    suspend fun logs(sessionId: Int): Result<LoggedSetsResponse> = runCatchingApi {
        apiClient.http.get("/sessions/$sessionId/logs").body()
    }
}
