package com.scorebook.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** DB 에 저장된 상대 경로 → 실제 파일 (Coil AsyncImage 모델로 사용) */
@Composable
fun rememberLibraryFile(relativePath: String?): File? {
    val context = LocalContext.current
    return remember(relativePath) {
        relativePath?.let { File(context.filesDir, it) }?.takeIf { it.exists() }
    }
}
