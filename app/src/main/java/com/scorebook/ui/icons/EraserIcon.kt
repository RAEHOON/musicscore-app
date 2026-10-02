package com.scorebook.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 비스듬히 놓인 네모난 지우개 (아래쪽 고무 부분이 채워진 모양) */
val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 지우개 몸통 외곽선 (45° 기울어진 직사각형)
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3.5f, 13.8f)
            lineTo(9.2f, 19.5f)
            lineTo(20.5f, 8.2f)
            lineTo(14.8f, 2.5f)
            close()
        }
        // 고무(지우는 쪽) 부분 채움
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.5f, 13.8f)
            lineTo(9.2f, 19.5f)
            lineTo(13.4f, 15.2f)
            lineTo(7.8f, 9.6f)
            close()
        }
        // 바닥선
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(10.5f, 21.2f)
            lineTo(20.5f, 21.2f)
        }
    }.build()
}
