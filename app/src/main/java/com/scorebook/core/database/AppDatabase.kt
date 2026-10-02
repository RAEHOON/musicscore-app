package com.scorebook.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScoreEntity::class,
        PageEntity::class,
        LayerEntity::class,
        StrokeEntity::class,
        ItemEntity::class,
        LinkEntity::class,
        BookmarkEntity::class,
        SetlistEntity::class,
        SetlistItemEntity::class,
        AudioEntity::class,
        PracticeSessionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao
    abstract fun pageDao(): PageDao
    abstract fun layerDao(): LayerDao
    abstract fun strokeDao(): StrokeDao
    abstract fun itemDao(): ItemDao
    abstract fun linkDao(): LinkDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun setlistDao(): SetlistDao
    abstract fun audioDao(): AudioDao
    abstract fun practiceDao(): PracticeDao

    companion object {
        const val NAME = "scorebook.db"

        /** v1 → v2: 링크에 이름 추가 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE links ADD COLUMN label TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
