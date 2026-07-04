package com.jauschua.ironlogv2.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.local.NoteDraft
import com.jauschua.ironlogv2.data.local.SurveyDraft
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureRepoReviewTest {
    private fun repo(): Pair<CaptureRepo, CaptureDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        // No network call is made by the review methods; a never-called engine is fine.
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        return CaptureRepo(ApiClient(engine = engine), db.captureDao()) to db
    }

    @Test fun saveGroupReview_writes_one_survey_per_exercise_plus_anchor_note() = runBlocking {
        val (repo, db) = repo()
        val surveys = listOf(
            SurveyDraft(sessionId = 7, movementId = 10, asymmetryFlag = true, techniqueFlag = false),
            SurveyDraft(sessionId = 7, movementId = 11, asymmetryFlag = false, techniqueFlag = false),
        )
        repo.saveGroupReview(7, surveys, anchorMovementId = 10, noteText = "rotator felt off")

        val dao = db.captureDao()
        assertEquals(listOf(10, 11), dao.surveysForSession(7).map { it.movementId })
        assertEquals("rotator felt off", dao.noteForMovement(7, 10)?.text)
        db.close()
    }

    @Test fun saveGroupReview_is_idempotent_replace() = runBlocking {
        val (repo, db) = repo()
        repo.saveGroupReview(7,
            listOf(SurveyDraft(sessionId = 7, movementId = 10, asymmetryFlag = true)),
            anchorMovementId = 10, noteText = "first")
        // Re-save the SAME group with different values → replace, not duplicate.
        repo.saveGroupReview(7,
            listOf(SurveyDraft(sessionId = 7, movementId = 10, asymmetryFlag = false)),
            anchorMovementId = 10, noteText = null)

        val dao = db.captureDao()
        val surveys = dao.surveysForSession(7)
        assertEquals(1, surveys.size)
        assertEquals(false, surveys.single().asymmetryFlag)
        assertNull(dao.noteForMovement(7, 10))   // cleared note on re-save with blank
        db.close()
    }

    @Test fun reviewDraftsFor_returns_prefill() = runBlocking {
        val (repo, db) = repo()
        repo.saveGroupReview(7,
            listOf(SurveyDraft(sessionId = 7, movementId = 10, techniqueFlag = true)),
            anchorMovementId = 10, noteText = "note10")
        val prefill = repo.reviewDraftsFor(7, listOf(10, 11), anchorMovementId = 10)
        assertEquals(true, prefill.surveys.single { it.movementId == 10 }.techniqueFlag)
        assertEquals("note10", prefill.noteText)
        db.close()
    }

    @Test fun saveSessionNote_upserts_null_movement_note() = runBlocking {
        val (repo, db) = repo()
        repo.saveSessionNote(7, "felt strong")
        assertEquals("felt strong", db.captureDao().sessionNote(7)?.text)
        repo.saveSessionNote(7, "   ")               // blank → delete
        assertNull(db.captureDao().sessionNote(7))
        db.close()
    }
}
