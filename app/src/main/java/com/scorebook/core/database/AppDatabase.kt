package com.scorebook.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
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
    }
}
