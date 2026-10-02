package com.scorebook.core.data

import com.scorebook.core.database.AppDatabase
import com.scorebook.core.database.SetlistEntity
import com.scorebook.core.database.SetlistItemEntity
import com.scorebook.core.model.Setlist
import com.scorebook.core.model.SetlistEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetlistRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.setlistDao()

    fun observeSetlists(): Flow<List<Setlist>> = dao.observeAll().map { rows ->
        rows.map { Setlist(it.id, it.name, it.itemCount, it.updatedAt) }
    }

    fun observeName(id: Long): Flow<String?> = dao.observe(id).map { it?.name }

    fun observeEntries(setlistId: Long): Flow<List<SetlistEntry>> =
        combine(dao.observeItems(setlistId), db.scoreDao().observeAll()) { items, scores ->
            val byId = scores.associateBy { it.id }
            items.mapNotNull { item ->
                val score = byId[item.scoreId] ?: return@mapNotNull null
                SetlistEntry(item.id, item.setlistId, item.position, score.toModel(), item.startPageId)
            }
        }

    suspend fun getEntries(setlistId: Long): List<SetlistEntry> {
        val items = dao.getItems(setlistId)
        val scores = db.scoreDao().getAll(items.map { it.scoreId }).associateBy { it.id }
        return items.mapNotNull { item ->
            val score = scores[item.scoreId] ?: return@mapNotNull null
            SetlistEntry(item.id, item.setlistId, item.position, score.toModel(), item.startPageId)
        }
    }

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(SetlistEntity(name = name, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun addScores(setlistId: Long, scoreIds: List<Long>) {
        var pos = dao.maxPosition(setlistId) + 1
        dao.insertItems(scoreIds.map { SetlistItemEntity(setlistId = setlistId, scoreId = it, position = pos++) })
        dao.touch(setlistId, System.currentTimeMillis())
    }

    suspend fun removeItem(setlistId: Long, itemId: Long) {
        dao.deleteItem(itemId)
        reorder(setlistId, dao.getItems(setlistId).map { it.id })
    }

    suspend fun reorder(setlistId: Long, orderedItemIds: List<Long>) {
        val items = dao.getItems(setlistId).associateBy { it.id }
        dao.updateItems(orderedItemIds.mapIndexedNotNull { i, id -> items[id]?.copy(position = i) })
        dao.touch(setlistId, System.currentTimeMillis())
    }

    suspend fun setStartPage(setlistId: Long, itemId: Long, pageId: Long?) {
        val item = dao.getItems(setlistId).firstOrNull { it.id == itemId } ?: return
        dao.updateItems(listOf(item.copy(startPageId = pageId)))
    }
}
