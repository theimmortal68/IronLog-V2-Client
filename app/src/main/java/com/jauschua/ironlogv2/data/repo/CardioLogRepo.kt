package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.data.api.dto.CardioLogOut
import com.jauschua.ironlogv2.data.api.dto.CardioWeeklySummaryOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Standalone Z2 cardio session logging -- log-only, no generation, no ProgramDay/day_role
 *  involvement, no progression engine. Mirrors the server's own standalone design. */
class CardioLogRepo(private val apiClient: ApiClient) {

    suspend fun create(req: CardioLogCreate): Result<CardioLogOut> = runCatchingApi {
        apiClient.http.post("/cardio-log") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun list(): Result<List<CardioLogOut>> = runCatchingApi {
        apiClient.http.get("/cardio-log").body()
    }

    suspend fun weeklySummary(): Result<CardioWeeklySummaryOut> = runCatchingApi {
        apiClient.http.get("/cardio-log/weekly-summary").body()
    }
}
