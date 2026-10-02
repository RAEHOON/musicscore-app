package com.scorebook.core.data

import com.scorebook.core.database.AudioEntity
import com.scorebook.core.database.BookmarkEntity
import com.scorebook.core.database.ItemEntity
import com.scorebook.core.database.LayerEntity
import com.scorebook.core.database.LinkEntity
import com.scorebook.core.database.PageEntity
import com.scorebook.core.database.ScoreEntity
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.AnnotationLayer
import com.scorebook.core.model.AudioClip
import com.scorebook.core.model.AudioKind
import com.scorebook.core.model.Bookmark
import com.scorebook.core.model.ItemKind
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.model.Score
import com.scorebook.core.model.ScoreLink
import com.scorebook.core.model.ScorePage
import com.scorebook.core.model.WorldRect

fun ScoreEntity.toModel() = Score(
    id = id,
    title = title,
    composer = composer,
    genre = genre,
    musicalKey = musicalKey,
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    favorite = favorite,
    pageCount = pageCount,
    lastPageIndex = lastPageIndex,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = lastOpenedAt,
    thumbnailPath = thumbnailPath,
    originalUri = originalUri,
)

fun PageEntity.toModel() = ScorePage(
    id = id,
    scoreId = scoreId,
    position = position,
    kind = PageSourceKind.entries[kind],
    sourcePath = sourcePath,
    sourceIndex = sourceIndex,
    width = width,
    height = height,
    rotation = rotation,
    crop = if (cropLeft != null && cropTop != null && cropRight != null && cropBottom != null) {
        PageCrop(cropLeft, cropTop, cropRight, cropBottom)
    } else {
        null
    },
)

fun LayerEntity.toModel() = AnnotationLayer(id, scoreId, name, visible, position)

fun ItemEntity.toModel() = AnnotationItem(
    id = id, pageId = pageId, layerId = layerId, kind = ItemKind.of(kind),
    x = x, y = y, size = size, color = color, text = text,
)

fun AnnotationItem.toEntity(createdAt: Long = System.currentTimeMillis()) = ItemEntity(
    id = id, pageId = pageId, layerId = layerId, kind = kind.code,
    x = x, y = y, size = size, color = color, text = text, createdAt = createdAt,
)

fun LinkEntity.toModel() = ScoreLink(id, scoreId, fromPageId, WorldRect(left, top, right, bottom), toPageId, label)

fun BookmarkEntity.toModel() = Bookmark(id, scoreId, pageId, title)

fun AudioEntity.toModel() = AudioClip(id, scoreId, title, path, AudioKind.of(kind), durationMs, createdAt)
