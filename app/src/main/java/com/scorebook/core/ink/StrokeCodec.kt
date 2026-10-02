package com.scorebook.core.ink

import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.scorebook.core.database.StrokeEntity
import com.scorebook.core.model.BrushKind
import com.scorebook.core.model.InkStroke
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** InkStroke ↔ DB 엔티티. 입력 포인트는 ink-storage 의 압축 포맷으로 저장한다. */
object StrokeCodec {

    fun toEntity(stroke: InkStroke, createdAt: Long = System.currentTimeMillis()): StrokeEntity {
        val bytes = ByteArrayOutputStream().use { out ->
            stroke.stroke.inputs.encode(out)
            out.toByteArray()
        }
        return StrokeEntity(
            id = stroke.id,
            pageId = stroke.pageId,
            layerId = stroke.layerId,
            brushKind = stroke.kind.code,
            color = stroke.color,
            size = stroke.size,
            inputs = bytes,
            createdAt = createdAt,
        )
    }

    fun fromEntity(entity: StrokeEntity): InkStroke? = runCatching {
        val kind = BrushKind.of(entity.brushKind)
        val inputs = ByteArrayInputStream(entity.inputs).use { StrokeInputBatch.decode(it) }
        InkStroke(
            id = entity.id,
            pageId = entity.pageId,
            layerId = entity.layerId,
            kind = kind,
            stroke = Stroke(InkBrushes.brush(kind, entity.color, entity.size), inputs),
        )
    }.getOrNull()
}
