package com.jauschua.ironlogv2.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SetLogDraft::class, SurveyDraft::class, NoteDraft::class],
    version = 2,
    exportSchema = false,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao
}

/**
 * v1 → v2: adds [SetLogDraft.sideIndex] (unilateral side discriminator; see its doc comment).
 * A non-destructive `ALTER TABLE ... ADD COLUMN` with DEFAULT 0 preserves any in-flight,
 * not-yet-submitted set drafts across an app update — the capture DB is the offline outbox, so a
 * destructive fallback here could silently drop a session logged just before the update.
 */
val CAPTURE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE setlog_draft ADD COLUMN sideIndex INTEGER NOT NULL DEFAULT 0")
    }
}
