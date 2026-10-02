package com.scorebook.core.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import com.scorebook.core.model.BrushKind

/** 브러시 종류 → Jetpack Ink Brush. 크기 단위는 PDF 포인트(월드 좌표). */
object InkBrushes {
    private val pen: BrushFamily by lazy { StockBrushes.pressurePen() }
    private val highlighter: BrushFamily by lazy { StockBrushes.highlighter(SelfOverlap.DISCARD) }
    private val marker: BrushFamily by lazy { StockBrushes.marker() }

    private const val EPSILON = 0.05f

    fun family(kind: BrushKind): BrushFamily = when (kind) {
        BrushKind.PEN -> pen
        BrushKind.HIGHLIGHTER -> highlighter
        BrushKind.MARKER -> marker
    }

    fun brush(kind: BrushKind, color: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(family(kind), color, size.coerceAtLeast(0.1f), EPSILON)

    val PEN_COLORS = listOf(
        0xFF111111.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(),
        0xFFEF6C00.toInt(), 0xFF6A1B9A.toInt(), 0xFF00838F.toInt(), 0xFF8D6E63.toInt(),
    )
    val HIGHLIGHTER_COLORS = listOf(
        0x66FFEB3B, 0x6681D4FA, 0x66A5D6A7, 0x66F48FB1, 0x66FFB74D,
    )
    val PEN_SIZES = listOf(0.6f, 1f, 1.6f, 2.4f, 3.6f)
    val HIGHLIGHTER_SIZES = listOf(6f, 9f, 13f, 18f)
}
