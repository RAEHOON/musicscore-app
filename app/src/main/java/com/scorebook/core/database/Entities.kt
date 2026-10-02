package com.scorebook.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scores",
    indices = [Index("sourceHash")],
)
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val composer: String = "",
    val genre: String = "",
    val musicalKey: String = "",
    /** 쉼표로 구분된 태그 */
    val tags: String = "",
    val favorite: Boolean = false,
    val pageCount: Int = 0,
    val lastPageIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long = 0,
    /** filesDir 기준 상대 경로 */
    val thumbnailPath: String? = null,
    val sourceHash: String? = null,
    /** 가져온 원본 파일 URI(쓰기 권한이 있으면 "원본에 저장" 가능) */
    val originalUri: String? = null,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val position: Int,
    /** PageSourceKind.ordinal */
    val kind: Int,
    /** filesDir 기준 상대 경로 (STAFF 는 null) */
    val sourcePath: String?,
    val sourceIndex: Int,
    val width: Float,
    val height: Float,
    val rotation: Int = 0,
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropRight: Float? = null,
    val cropBottom: Float? = null,
)

@Entity(
    tableName = "layers",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId")],
)
data class LayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val name: String,
    val visible: Boolean = true,
    val position: Int = 0,
)

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LayerEntity::class, ["id"], ["layerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("pageId"), Index("layerId")],
)
data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: Long,
    val layerId: Long,
    val brushKind: Int,
    val color: Int,
    val size: Float,
    /** androidx.ink.storage 로 인코딩된 StrokeInputBatch (압축 바이너리) */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val inputs: ByteArray,
    val createdAt: Long,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LayerEntity::class, ["id"], ["layerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("pageId"), Index("layerId")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val pageId: Long,
    val layerId: Long,
    val kind: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
    val text: String,
    val createdAt: Long,
)

@Entity(
    tableName = "links",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PageEntity::class, ["id"], ["fromPageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PageEntity::class, ["id"], ["toPageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId"), Index("fromPageId"), Index("toPageId")],
)
data class LinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val fromPageId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val toPageId: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId"), Index("pageId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val pageId: Long,
    val title: String,
)

@Entity(tableName = "setlists")
data class SetlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "setlist_items",
    foreignKeys = [
        ForeignKey(SetlistEntity::class, ["id"], ["setlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("setlistId"), Index("scoreId")],
)
data class SetlistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setlistId: Long,
    val scoreId: Long,
    val position: Int,
    val startPageId: Long? = null,
)

@Entity(
    tableName = "audio",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId")],
)
data class AudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val title: String,
    val path: String,
    val kind: Int,
    val durationMs: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "practice_sessions",
    foreignKeys = [
        ForeignKey(ScoreEntity::class, ["id"], ["scoreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("scoreId"), Index("startedAt")],
)
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scoreId: Long,
    val startedAt: Long,
    val durationSec: Long,
)

/** Room 조회 결과용 */
data class SetlistWithCount(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val itemCount: Int,
)

data class PracticeTotalRow(
    val scoreId: Long,
    val title: String,
    val seconds: Long,
    val sessions: Int,
)
