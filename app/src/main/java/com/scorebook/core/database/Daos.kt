package com.scorebook.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreDao {
    @Query("SELECT * FROM scores")
    fun observeAll(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE id = :id")
    fun observe(id: Long): Flow<ScoreEntity?>

    @Query("SELECT * FROM scores WHERE id = :id")
    suspend fun get(id: Long): ScoreEntity?

    @Query("SELECT * FROM scores WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE sourceHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): ScoreEntity?

    @Insert
    suspend fun insert(score: ScoreEntity): Long

    @Update
    suspend fun update(score: ScoreEntity)

    @Query("DELETE FROM scores WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE scores SET lastPageIndex = :page, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateLastPage(id: Long, page: Int, openedAt: Long)

    @Query("UPDATE scores SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE scores SET pageCount = :count, updatedAt = :now WHERE id = :id")
    suspend fun updatePageCount(id: Long, count: Int, now: Long)

    @Query("UPDATE scores SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnail(id: Long, path: String?)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE scoreId = :scoreId ORDER BY position")
    fun observeForScore(scoreId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE scoreId = :scoreId ORDER BY position")
    suspend fun getForScore(scoreId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PageEntity>

    @Query("SELECT sourcePath FROM pages WHERE sourcePath IS NOT NULL")
    suspend fun allSourcePaths(): List<String>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Insert
    suspend fun insertAll(pages: List<PageEntity>): List<Long>

    @Update
    suspend fun updateAll(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}

@Dao
interface LayerDao {
    @Query("SELECT * FROM layers WHERE scoreId = :scoreId ORDER BY position")
    fun observeForScore(scoreId: Long): Flow<List<LayerEntity>>

    @Query("SELECT * FROM layers WHERE scoreId = :scoreId ORDER BY position")
    suspend fun getForScore(scoreId: Long): List<LayerEntity>

    @Insert
    suspend fun insert(layer: LayerEntity): Long

    @Update
    suspend fun update(layer: LayerEntity)

    @Query("DELETE FROM layers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StrokeDao {
    @Query("SELECT s.* FROM strokes s JOIN pages p ON s.pageId = p.id WHERE p.scoreId = :scoreId ORDER BY s.createdAt")
    suspend fun getForScore(scoreId: Long): List<StrokeEntity>

    @Upsert
    suspend fun upsertAll(strokes: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
}

@Dao
interface ItemDao {
    @Query("SELECT i.* FROM items i JOIN pages p ON i.pageId = p.id WHERE p.scoreId = :scoreId ORDER BY i.createdAt")
    suspend fun getForScore(scoreId: Long): List<ItemEntity>

    @Upsert
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
}

@Dao
interface LinkDao {
    @Query("SELECT * FROM links WHERE scoreId = :scoreId")
    fun observeForScore(scoreId: Long): Flow<List<LinkEntity>>

    @Query("SELECT * FROM links WHERE scoreId = :scoreId")
    suspend fun getForScore(scoreId: Long): List<LinkEntity>

    @Insert
    suspend fun insert(link: LinkEntity): Long

    @Insert
    suspend fun insertAll(links: List<LinkEntity>)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT b.* FROM bookmarks b JOIN pages p ON b.pageId = p.id WHERE b.scoreId = :scoreId ORDER BY p.position")
    fun observeForScore(scoreId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE scoreId = :scoreId")
    suspend fun getForScore(scoreId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Insert
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Query("UPDATE bookmarks SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SetlistDao {
    @Query(
        """
        SELECT s.id, s.name, s.updatedAt, COUNT(i.id) AS itemCount
        FROM setlists s LEFT JOIN setlist_items i ON i.setlistId = s.id
        GROUP BY s.id ORDER BY s.updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<SetlistWithCount>>

    @Query("SELECT * FROM setlists WHERE id = :id")
    fun observe(id: Long): Flow<SetlistEntity?>

    @Query("SELECT * FROM setlist_items WHERE setlistId = :setlistId ORDER BY position")
    fun observeItems(setlistId: Long): Flow<List<SetlistItemEntity>>

    @Query("SELECT * FROM setlist_items WHERE setlistId = :setlistId ORDER BY position")
    suspend fun getItems(setlistId: Long): List<SetlistItemEntity>

    @Insert
    suspend fun insert(setlist: SetlistEntity): Long

    @Query("UPDATE setlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE setlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM setlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertItems(items: List<SetlistItemEntity>)

    @Update
    suspend fun updateItems(items: List<SetlistItemEntity>)

    @Query("DELETE FROM setlist_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM setlist_items WHERE setlistId = :setlistId")
    suspend fun maxPosition(setlistId: Long): Int
}

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio WHERE scoreId = :scoreId ORDER BY createdAt DESC")
    fun observeForScore(scoreId: Long): Flow<List<AudioEntity>>

    @Query("SELECT * FROM audio WHERE scoreId = :scoreId")
    suspend fun getForScore(scoreId: Long): List<AudioEntity>

    @Query("SELECT path FROM audio")
    suspend fun allPaths(): List<String>

    @Insert
    suspend fun insert(audio: AudioEntity): Long

    @Query("DELETE FROM audio WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM audio WHERE id = :id")
    suspend fun get(id: Long): AudioEntity?
}

@Dao
interface PracticeDao {
    @Insert
    suspend fun insert(session: PracticeSessionEntity)

    @Query("SELECT * FROM practice_sessions WHERE startedAt >= :from ORDER BY startedAt")
    fun observeSince(from: Long): Flow<List<PracticeSessionEntity>>

    @Query(
        """
        SELECT p.scoreId AS scoreId, s.title AS title, SUM(p.durationSec) AS seconds, COUNT(p.id) AS sessions
        FROM practice_sessions p JOIN scores s ON s.id = p.scoreId
        GROUP BY p.scoreId ORDER BY seconds DESC
        """
    )
    fun observeTotals(): Flow<List<PracticeTotalRow>>
}
