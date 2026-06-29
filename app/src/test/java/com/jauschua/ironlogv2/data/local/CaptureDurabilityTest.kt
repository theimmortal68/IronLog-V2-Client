package com.jauschua.ironlogv2.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CaptureDurabilityTest {
    private fun dbFile() = File.createTempFile("capture-test", ".db").apply { delete() }

    @Test
    fun setLogs_survive_db_instance_recreation() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = dbFile()
        // First "process": write three taps, each commits before returning.
        var db = Room.databaseBuilder(ctx, CaptureDatabase::class.java, file.absolutePath).build()
        repeat(3) { i ->
            db.captureDao().insertSetLog(
                SetLogDraft(
                    sessionId = 7,
                    plannedSetId = i,
                    movementId = 3,
                    setIndex = i,
                    setRole = "WORKING",
                    isWarmup = false,
                    actualLoad = 100.0,
                    actualReps = 8,
                    feedbackTap = "ON_TARGET",
                )
            )
        }
        db.close()  // simulate process death — only on-disk state survives
        // Second "process": reopen, assert all three recovered.
        db = Room.databaseBuilder(ctx, CaptureDatabase::class.java, file.absolutePath).build()
        val recovered = db.captureDao().setLogsForSession(7)
        assertEquals(3, recovered.size)
        assertEquals("ON_TARGET", recovered.first().feedbackTap)
        db.close()
    }
}
