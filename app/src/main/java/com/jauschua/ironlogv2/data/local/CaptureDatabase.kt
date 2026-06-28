package com.jauschua.ironlogv2.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SetLogDraft::class, SurveyDraft::class, NoteDraft::class],
    version = 1,
    exportSchema = false,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao
}
