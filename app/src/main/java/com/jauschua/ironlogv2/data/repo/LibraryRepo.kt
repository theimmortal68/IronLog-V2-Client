package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.BandPairDto
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get

class LibraryRepo(private val apiClient: ApiClient) {

    suspend fun movements(): Result<List<MovementDto>> = runCatchingApi {
        apiClient.http.get("/movements").body()
    }

    suspend fun getMovement(id: Int): Result<MovementDto> = runCatchingApi {
        apiClient.http.get("/movements/$id").body()
    }

    suspend fun usableBands(): Result<List<BandPairDto>> = runCatchingApi {
        apiClient.http.get("/bands/usable").body()
    }
}
