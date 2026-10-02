package com.scorebook.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageGeometry
import com.scorebook.core.render.PageRenderer

/** 페이지 미리보기 (페이지 목록, 링크 대상 선택, 페이지 편집에서 사용) */
@Composable
fun PageThumbnail(
    page: ScorePage,
    renderer: PageRenderer,
    modifier: Modifier = Modifier,
    widthPx: Int = 260,
) {
    val geometry = PageGeometry.of(page)
    val bitmap by produceState<ImageBitmap?>(
        initialValue = renderer.cached(page, geometry, widthPx)?.asImageBitmap(),
        page.id, page.rotation, page.crop, page.sourcePath, page.sourceIndex,
    ) {
        value = (renderer.cached(page, geometry, widthPx) ?: renderer.render(page, geometry, widthPx))?.asImageBitmap()
    }
    Box(modifier.aspectRatio(geometry.aspectRatio).background(Color.White)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}
