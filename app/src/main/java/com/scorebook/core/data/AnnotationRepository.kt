package com.scorebook.core.data

import com.scorebook.core.database.AppDatabase
import com.scorebook.core.database.BookmarkEntity
import com.scorebook.core.database.LayerEntity
import com.scorebook.core.database.LinkEntity
import com.scorebook.core.ink.StrokeCodec
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.AnnotationLayer
import com.scorebook.core.model.Bookmark
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.ScoreLink
import com.scorebook.core.model.WorldRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(private val db: AppDatabase) {

    // ----- 레이어 -----

    fun observeLayers(scoreId: Long): Flow<List<AnnotationLayer>> =
        db.layerDao().observeForScore(scoreId).map { list -> list.map { it.toModel() } }

    suspend fun getLayers(scoreId: Long): List<AnnotationLayer> = db.layerDao().getForScore(scoreId).map { it.toModel() }

    suspend fun addLayer(scoreId: Long, name: String): Long {
        val position = db.layerDao().getForScore(scoreId).size
        return db.layerDao().insert(LayerEntity(scoreId = scoreId, name = name, position = position))
    }

    suspend fun updateLayer(layer: AnnotationLayer) = db.layerDao().update(
        LayerEntity(layer.id, layer.scoreId, layer.name, layer.visible, layer.position)
    )

    suspend fun deleteLayer(id: Long) = db.layerDao().delete(id)

    // ----- 잉크 획 -----

    suspend fun loadStrokes(scoreId: Long): List<InkStroke> {
        val entities = db.strokeDao().getForScore(scoreId)
        return withContext(Dispatchers.Default) { entities.mapNotNull(StrokeCodec::fromEntity) }
    }

    suspend fun saveStrokes(strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        val entities = withContext(Dispatchers.Default) { strokes.map { StrokeCodec.toEntity(it) } }
        db.strokeDao().upsertAll(entities)
    }

    suspend fun deleteStrokes(ids: Collection<String>) {
        if (ids.isNotEmpty()) db.strokeDao().deleteIds(ids.toList())
    }

    // ----- 텍스트/스탬프 -----

    suspend fun loadItems(scoreId: Long): List<AnnotationItem> = db.itemDao().getForScore(scoreId).map { it.toModel() }

    suspend fun saveItems(items: List<AnnotationItem>) {
        if (items.isNotEmpty()) db.itemDao().upsertAll(items.map { it.toEntity() })
    }

    suspend fun deleteItems(ids: Collection<String>) {
        if (ids.isNotEmpty()) db.itemDao().deleteIds(ids.toList())
    }

    // ----- 링크 -----

    fun observeLinks(scoreId: Long): Flow<List<ScoreLink>> =
        db.linkDao().observeForScore(scoreId).map { list -> list.map { it.toModel() } }

    suspend fun addLink(scoreId: Long, fromPageId: Long, rect: WorldRect, toPageId: Long) {
        db.linkDao().insert(
            LinkEntity(
                scoreId = scoreId, fromPageId = fromPageId,
                left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                toPageId = toPageId,
            )
        )
    }

    suspend fun deleteLink(id: Long) = db.linkDao().delete(id)

    suspend fun updateLink(id: Long, label: String, toPageId: Long) = db.linkDao().update(id, label.trim(), toPageId)

    // ----- 북마크 -----

    fun observeBookmarks(scoreId: Long): Flow<List<Bookmark>> =
        db.bookmarkDao().observeForScore(scoreId).map { list -> list.map { it.toModel() } }

    suspend fun getBookmarks(scoreId: Long): List<Bookmark> = db.bookmarkDao().getForScore(scoreId).map { it.toModel() }

    suspend fun addBookmark(scoreId: Long, pageId: Long, title: String) {
        db.bookmarkDao().insert(BookmarkEntity(scoreId = scoreId, pageId = pageId, title = title))
    }

    suspend fun renameBookmark(id: Long, title: String) = db.bookmarkDao().rename(id, title)

    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().delete(id)
}
