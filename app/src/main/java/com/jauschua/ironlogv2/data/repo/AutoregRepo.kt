package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
import com.jauschua.ironlogv2.data.api.dto.NextSetResponse
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AutoregRepo(private val apiClient: ApiClient) {

    suspend fun nextSet(req: NextSetRequest): Result<NextSetResponse> = runCatchingApi {
        apiClient.http.post("/autoregulate/next-set") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }
}
