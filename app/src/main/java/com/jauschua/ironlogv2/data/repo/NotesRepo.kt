package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ApplyNoteRequest
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Unconfirmed change-proposals (CONFIG_CHANGE / PROGRAMMING_REQUEST) surfaced for user review,
 *  plus the deterministic apply/override path for CONFIG_CHANGE swaps. Confirm/dismiss only flip
 *  server-side flags; apply creates a slot-level override (still no client-side program mutation —
 *  the server owns program state, this only tells it which movement to swap to). */
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

    suspend fun apply(id: Int, targetMovementId: Int): Result<Unit> = runCatchingApi {
        apiClient.http.post("/notes/$id/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyNoteRequest(targetMovementId))
        }
        Unit
    }

    suspend fun overrides(): Result<List<OverrideOut>> = runCatchingApi {
        apiClient.http.get("/overrides").body()
    }

    suspend fun revert(id: Int): Result<Unit> = runCatchingApi {
        apiClient.http.post("/overrides/$id/revert"); Unit
    }
}
