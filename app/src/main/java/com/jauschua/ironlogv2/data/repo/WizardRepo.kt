package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.StartProgramResponse
import com.jauschua.ironlogv2.data.api.dto.WizardResolution
import com.jauschua.ironlogv2.data.api.dto.WizardResolveRequest
import com.jauschua.ironlogv2.data.api.dto.WizardResolveResponse
import com.jauschua.ironlogv2.data.api.dto.WizardStateResponse
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class WizardRepo(private val apiClient: ApiClient) {

    suspend fun state(programId: Int): Result<WizardStateResponse> = runCatchingApi {
        apiClient.http.get("/programs/$programId/wizard-state").body()
    }

    suspend fun resolve(
        programId: Int,
        resolutions: List<WizardResolution>,
    ): Result<WizardResolveResponse> = runCatchingApi {
        apiClient.http.post("/programs/$programId/wizard-resolve") {
            contentType(ContentType.Application.Json)
            setBody(WizardResolveRequest(resolutions))
        }.body()
    }

    suspend fun start(programId: Int): Result<StartProgramResponse> = runCatchingApi {
        apiClient.http.post("/programs/$programId/start") {
            contentType(ContentType.Application.Json)
        }.body()
    }
}
