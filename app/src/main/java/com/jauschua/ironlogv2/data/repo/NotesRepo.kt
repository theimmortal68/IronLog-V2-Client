package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ApplyOverrideRequest
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.data.api.dto.ProgramSlotOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Unconfirmed change-proposals (CONFIG_CHANGE / PROGRAMMING_REQUEST) surfaced for user review,
 *  plus the deterministic explicit-apply path (source slot + action-routed override). Confirm/
 *  dismiss only flip server-side flags; apply POSTs an athlete-confirmed slot + override — the
 *  server never infers the slot from the note's attachment (still no client-side program
 *  mutation — the server owns program state and creates the override). */
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

    /** The active program's exercise slots, for the source-slot confirm/pick step. */
    suspend fun programSlots(programId: Int): Result<List<ProgramSlotOut>> = runCatchingApi {
        apiClient.http.get("/programs/$programId/slots").body()
    }

    suspend fun applyOverride(noteId: Int, req: ApplyOverrideRequest): Result<Unit> = runCatchingApi {
        apiClient.http.post("/notes/$noteId/apply") {
            contentType(ContentType.Application.Json)
            setBody(req)
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
