package com.scorebook.core.data

import androidx.room.withTransaction
import com.scorebook.core.database.AppDatabase
import com.scorebook.core.database.BookmarkEntity
import com.scorebook.core.database.LayerEntity
import com.scorebook.core.database.LinkEntity
import com.scorebook.core.database.PageEntity
import com.scorebook.core.database.ScoreEntity
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.model.Score
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageRenderer
import com.scorebook.core.render.StaffPaper
import com.scorebook.core.storage.LibraryFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoreRepository @Inject constructor(
    private val db: AppDatabase,
    private val files: LibraryFiles,
    private val renderer: PageRenderer,
) {
    private val scoreDao = db.scoreDao()
    private val pageDao = db.pageDao()

    fun observeScores(): Flow<List<Score>> = scoreDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeScore(id: Long): Flow<Score?> = scoreDao.observe(id).map { it?.toModel() }

    suspend fun getScore(id: Long): Score? = scoreDao.get(id)?.toModel()

    fun observePages(scoreId: Long): Flow<List<ScorePage>> =
        pageDao.observeForScore(scoreId).map { list -> list.map { it.toModel() } }

    suspend fun getPages(scoreId: Long): List<ScorePage> = pageDao.getForScore(scoreId).map { it.toModel() }

    suspend fun findByHash(hash: String): Score? = scoreDao.findByHash(hash)?.toModel()

    /** 새 악보 생성: 점수 + 페이지 + 기본 레이어 + 썸네일 */
    suspend fun createScore(
        title: String,
        pages: List<PageEntity>,
        originalUri: String? = null,
        sourceHash: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val id = db.withTransaction {
            val scoreId = scoreDao.insert(
                ScoreEntity(
                    title = title,
                    pageCount = pages.size,
                    createdAt = now,
                    updatedAt = now,
                    sourceHash = sourceHash,
                    originalUri = originalUri,
                )
            )
            pageDao.insertAll(pages.mapIndexed { i, p -> p.copy(id = 0, scoreId = scoreId, position = i) })
            db.layerDao().insert(LayerEntity(scoreId = scoreId, name = DEFAULT_LAYER, position = 0))
            scoreId
        }
        regenerateThumbnail(id)
        return id
    }

    suspend fun updateInfo(id: Long, title: String, composer: String, genre: String, key: String, tags: List<String>) {
        val current = scoreDao.get(id) ?: return
        scoreDao.update(
            current.copy(
                title = title.ifBlank { current.title },
                composer = composer,
                genre = genre,
                musicalKey = key,
                tags = tags.joinToString(","),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = scoreDao.setFavorite(id, favorite)

    suspend fun setLastPage(id: Long, index: Int) = scoreDao.updateLastPage(id, index, System.currentTimeMillis())

    suspend fun deleteScores(ids: Collection<Long>) {
        val candidatePaths = mutableSetOf<String>()
        for (id in ids) {
            val score = scoreDao.get(id) ?: continue
            pageDao.getForScore(id).mapNotNullTo(candidatePaths) { it.sourcePath }
            db.audioDao().getForScore(id).mapTo(candidatePaths) { it.path }
            files.delete(score.thumbnailPath)
            scoreDao.delete(id)
        }
        // 합치기로 생성된 악보는 같은 원본 파일을 공유할 수 있으므로 더 이상 참조되지 않는 파일만 삭제
        val stillUsed = pageDao.allSourcePaths().toSet() + db.audioDao().allPaths().toSet()
        candidatePaths.filterNot { it in stillUsed }.forEach { files.delete(it) }
    }

    suspend fun regenerateThumbnail(scoreId: Long) {
        val first = pageDao.getForScore(scoreId).firstOrNull()?.toModel()
        val old = scoreDao.get(scoreId)?.thumbnailPath
        if (first == null) {
            scoreDao.updateThumbnail(scoreId, null)
        } else {
            val (rel, file) = files.newThumbnail(scoreId)
            if (renderer.writeThumbnail(first, file)) scoreDao.updateThumbnail(scoreId, rel)
        }
        files.delete(old)
    }

    // ----- 페이지 편집 (모두 메타데이터만 변경, 원본 파일은 그대로) -----

    suspend fun reorderPages(scoreId: Long, orderedIds: List<Long>) {
        val pages = pageDao.getForScore(scoreId).associateBy { it.id }
        val updated = orderedIds.mapIndexedNotNull { i, id -> pages[id]?.copy(position = i) }
        pageDao.updateAll(updated)
        touch(scoreId)
        regenerateThumbnail(scoreId)
    }

    suspend fun rotatePages(scoreId: Long, ids: Collection<Long>, delta: Int) {
        val pages = pageDao.getByIds(ids.toList())
        pageDao.updateAll(pages.map { it.copy(rotation = ((it.rotation + delta) % 360 + 360) % 360) })
        touch(scoreId)
        regenerateThumbnail(scoreId)
    }

    suspend fun deletePages(scoreId: Long, ids: Collection<Long>) {
        db.withTransaction {
            pageDao.deleteIds(ids.toList())
            normalizePositions(scoreId)
        }
        regenerateThumbnail(scoreId)
    }

    suspend fun insertStaffPage(scoreId: Long, afterPosition: Int) {
        db.withTransaction {
            val pages = pageDao.getForScore(scoreId)
            val shifted = pages.map { if (it.position > afterPosition) it.copy(position = it.position + 1) else it }
            pageDao.updateAll(shifted)
            pageDao.insert(
                PageEntity(
                    scoreId = scoreId,
                    position = afterPosition + 1,
                    kind = PageSourceKind.STAFF.ordinal,
                    sourcePath = null,
                    sourceIndex = 0,
                    width = StaffPaper.WIDTH,
                    height = StaffPaper.HEIGHT,
                )
            )
            normalizePositions(scoreId)
        }
        regenerateThumbnail(scoreId)
    }

    suspend fun setCrop(scoreId: Long, pageIds: Collection<Long>, crop: PageCrop?) {
        val pages = pageDao.getByIds(pageIds.toList())
        pageDao.updateAll(
            pages.map {
                it.copy(cropLeft = crop?.left, cropTop = crop?.top, cropRight = crop?.right, cropBottom = crop?.bottom)
            }
        )
        touch(scoreId)
        regenerateThumbnail(scoreId)
    }

    /** PDF 합치기: 여러 악보의 페이지(+필기/북마크/링크)를 순서대로 이어 붙인 새 악보 */
    suspend fun mergeScores(scoreIds: List<Long>, title: String): Long {
        val newId = db.withTransaction {
            val now = System.currentTimeMillis()
            val newScoreId = scoreDao.insert(ScoreEntity(title = title, createdAt = now, updatedAt = now))
            val layerIdsByName = mutableMapOf<String, Long>()
            var position = 0
            for (srcId in scoreIds) {
                val pages = pageDao.getForScore(srcId)
                position = copyPagesInto(srcId, pages, newScoreId, position, layerIdsByName)
            }
            if (layerIdsByName.isEmpty()) {
                db.layerDao().insert(LayerEntity(scoreId = newScoreId, name = DEFAULT_LAYER))
            }
            scoreDao.updatePageCount(newScoreId, position, now)
            newScoreId
        }
        regenerateThumbnail(newId)
        return newId
    }

    /** PDF 나누기: 선택한 페이지만 새 악보로 복사 */
    suspend fun splitScore(scoreId: Long, pageIds: Collection<Long>, title: String): Long {
        val newId = db.withTransaction {
            val now = System.currentTimeMillis()
            val newScoreId = scoreDao.insert(ScoreEntity(title = title, createdAt = now, updatedAt = now))
            val pages = pageDao.getForScore(scoreId).filter { it.id in pageIds }
            val layers = mutableMapOf<String, Long>()
            val count = copyPagesInto(scoreId, pages, newScoreId, 0, layers)
            if (layers.isEmpty()) db.layerDao().insert(LayerEntity(scoreId = newScoreId, name = DEFAULT_LAYER))
            scoreDao.updatePageCount(newScoreId, count, now)
            newScoreId
        }
        regenerateThumbnail(newId)
        return newId
    }

    private suspend fun copyPagesInto(
        srcScoreId: Long,
        pages: List<PageEntity>,
        dstScoreId: Long,
        startPosition: Int,
        layerIdsByName: MutableMap<String, Long>,
    ): Int {
        var position = startPosition
        val pageIdMap = mutableMapOf<Long, Long>()
        for (p in pages) {
            pageIdMap[p.id] = pageDao.insert(p.copy(id = 0, scoreId = dstScoreId, position = position++))
        }
        val srcLayers = db.layerDao().getForScore(srcScoreId)
        val layerMap = srcLayers.associate { layer ->
            layer.id to layerIdsByName.getOrPut(layer.name) {
                db.layerDao().insert(
                    LayerEntity(scoreId = dstScoreId, name = layer.name, visible = layer.visible, position = layerIdsByName.size)
                )
            }
        }
        val strokes = db.strokeDao().getForScore(srcScoreId).filter { it.pageId in pageIdMap }
        db.strokeDao().upsertAll(
            strokes.mapNotNull { s ->
                val layer = layerMap[s.layerId] ?: return@mapNotNull null
                s.copy(id = UUID.randomUUID().toString(), pageId = pageIdMap.getValue(s.pageId), layerId = layer)
            }
        )
        val items = db.itemDao().getForScore(srcScoreId).filter { it.pageId in pageIdMap }
        db.itemDao().upsertAll(
            items.mapNotNull { i ->
                val layer = layerMap[i.layerId] ?: return@mapNotNull null
                i.copy(id = UUID.randomUUID().toString(), pageId = pageIdMap.getValue(i.pageId), layerId = layer)
            }
        )
        val bookmarks = db.bookmarkDao().getForScore(srcScoreId).filter { it.pageId in pageIdMap }
        db.bookmarkDao().insertAll(
            bookmarks.map { BookmarkEntity(scoreId = dstScoreId, pageId = pageIdMap.getValue(it.pageId), title = it.title) }
        )
        val links = db.linkDao().getForScore(srcScoreId).filter { it.fromPageId in pageIdMap && it.toPageId in pageIdMap }
        db.linkDao().insertAll(
            links.map {
                LinkEntity(
                    scoreId = dstScoreId,
                    fromPageId = pageIdMap.getValue(it.fromPageId),
                    left = it.left, top = it.top, right = it.right, bottom = it.bottom,
                    toPageId = pageIdMap.getValue(it.toPageId),
                )
            }
        )
        return position
    }

    private suspend fun normalizePositions(scoreId: Long) {
        val pages = pageDao.getForScore(scoreId)
        pageDao.updateAll(pages.mapIndexed { i, p -> p.copy(position = i) })
        scoreDao.updatePageCount(scoreId, pages.size, System.currentTimeMillis())
    }

    private suspend fun touch(scoreId: Long) {
        scoreDao.updatePageCount(scoreId, pageDao.getForScore(scoreId).size, System.currentTimeMillis())
    }

    companion object {
        const val DEFAULT_LAYER = "기본"
    }
}
