package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ConfirmPhaseRequest
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessIn
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class ReadinessRepo(private val apiClient: ApiClient) {

    suspend fun today(): Result<DailyReadinessOut> = runCatchingApi {
        apiClient.http.get("/readiness/today").body()
    }

    suspend fun checkIn(req: DailyReadinessIn): Result<DailyReadinessOut> = runCatchingApi {
        apiClient.http.post("/readiness") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun confirmPhase(toPhase: String): Result<Unit> = runCatchingApi {
        apiClient.http.post("/engine-state/confirm-phase") {
            contentType(ContentType.Application.Json)
            setBody(ConfirmPhaseRequest(to_phase = toPhase))
        }.body()
    }
}
