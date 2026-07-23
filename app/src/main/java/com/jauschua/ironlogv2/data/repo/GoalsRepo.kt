package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsIn
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GoalsRepo(private val apiClient: ApiClient) {

    suspend fun get(): Result<GoalSettingsOut?> = runCatchingApi {
        apiClient.http.get("/goals").body()
    }

    suspend fun update(req: GoalSettingsIn): Result<GoalSettingsOut> = runCatchingApi {
        apiClient.http.post("/goals") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }
}
