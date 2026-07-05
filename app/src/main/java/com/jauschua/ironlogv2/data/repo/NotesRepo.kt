package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post

/** Unconfirmed change-proposals (CONFIG_CHANGE / PROGRAMMING_REQUEST) surfaced for user review.
 *  No auto-apply: confirm/dismiss only flip server-side flags, never mutate program state here. */
class NotesRepo(private val apiClient: ApiClient) {

    suspend fun review(): Result<List<NoteReviewOut>> = runCatchingApi {
        apiClient.http.get("/notes/review").body()
    }

    suspend fun confirm(id: Int): Result<Unit> = runCatchingApi {
        apiClient.http.post("/notes/$id/confirm"); Unit
    }

    suspend fun dismiss(id: Int): Result<Unit> = runCatchingApi {
        apiClient.http.post("/notes/$id/dismiss"); Unit
    }
}
