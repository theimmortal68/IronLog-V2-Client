package com.jauschua.ironlogv2.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureReviewDaoTest {
    private lateinit var db: CaptureDatabase
    private lateinit var dao: CaptureDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.captureDao()
    }

    @After fun teardown() = db.close()

    @Test fun deleteSurveysForMovements_only_touches_listed_movements() = runBlocking {
        dao.insertSurvey(SurveyDraft(sessionId = 7, movementId = 1, asymmetryFlag = true))
        dao.insertSurvey(SurveyDraft(sessionId = 7, movementId = 2, techniqueFlag = true))
        dao.insertSurvey(SurveyDraft(sessionId = 7, movementId = 3))
        dao.insertSurvey(SurveyDraft(sessionId = 9, movementId = 1))   // other session

        dao.deleteSurveysForMovements(7, listOf(1, 2))

        assertEquals(listOf(3), dao.surveysForSession(7).map { it.movementId })
        assertEquals(1, dao.surveysForSession(9).size)   // other session untouched
    }

    @Test fun surveysForMovements_filters_by_session_and_movement_list() = runBlocking {
        dao.insertSurvey(SurveyDraft(sessionId = 7, movementId = 1, asymmetryFlag = true))
        dao.insertSurvey(SurveyDraft(sessionId = 7, movementId = 5))
        val got = dao.surveysForMovements(7, listOf(1, 2, 3))
        assertEquals(listOf(1), got.map { it.movementId })
    }

    @Test fun group_note_and_session_note_are_independent() = runBlocking {
        dao.insertNote(NoteDraft(sessionId = 7, movementId = 4, text = "group note"))
        dao.insertNote(NoteDraft(sessionId = 7, movementId = null, text = "session note"))

        assertEquals("group note", dao.noteForMovement(7, 4)?.text)
        assertEquals("session note", dao.sessionNote(7)?.text)

        dao.deleteNoteForMovement(7, 4)
        assertNull(dao.noteForMovement(7, 4))
        assertEquals("session note", dao.sessionNote(7)?.text)   // session note survives

        dao.deleteSessionNote(7)
        assertNull(dao.sessionNote(7))
    }
}
