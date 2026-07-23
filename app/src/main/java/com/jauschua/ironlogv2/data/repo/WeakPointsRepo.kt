package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.WeakPointAssessmentOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get

class WeakPointsRepo(private val apiClient: ApiClient) {
    suspend fun assessment(): Result<WeakPointAssessmentOut> = runCatchingApi {
        apiClient.http.get("/weak-points").body()
    }
}
