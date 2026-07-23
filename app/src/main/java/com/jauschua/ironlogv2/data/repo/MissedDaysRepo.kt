package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.MissedDayRecordOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post

class MissedDaysRepo(private val apiClient: ApiClient) {

    suspend fun list(): Result<List<MissedDayRecordOut>> = runCatchingApi {
        apiClient.http.get("/missed-days").body()
    }

    suspend fun acknowledge(recordId: Int): Result<MissedDayRecordOut> = runCatchingApi {
        apiClient.http.post("/missed-days/$recordId/acknowledge").body()
    }

    suspend fun reschedule(recordId: Int): Result<MissedDayRecordOut> = runCatchingApi {
        apiClient.http.post("/missed-days/$recordId/reschedule").body()
    }
}
