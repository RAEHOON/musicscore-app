package com.scorebook.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱 내부 저장소 레이아웃. DB 에는 항상 filesDir 기준 상대 경로를 저장해서
 * 백업 zip 을 다른 기기에 복원해도 경로가 깨지지 않게 한다.
 *
 * files/
 *   library/pdf/{uuid}.pdf
 *   library/images/{uuid}.jpg
 *   library/thumbs/{scoreId}_{ts}.jpg
 *   library/audio/{uuid}.m4a
 */
@Singleton
class LibraryFiles @Inject constructor(@ApplicationContext private val context: Context) {

    val filesDir: File get() = context.filesDir
    val root: File get() = File(filesDir, ROOT).apply { mkdirs() }

    fun resolve(relative: String): File = File(filesDir, relative)

    fun newPdf(): Pair<String, File> = newFile("pdf", "pdf")
    fun newImage(): Pair<String, File> = newFile("images", "jpg")
    fun newAudio(ext: String): Pair<String, File> = newFile("audio", ext)

    fun newThumbnail(scoreId: Long): Pair<String, File> {
        val rel = "$ROOT/thumbs/${scoreId}_${System.currentTimeMillis()}.jpg"
        return rel to resolve(rel).apply { parentFile?.mkdirs() }
    }

    fun tempFile(prefix: String, ext: String): File =
        File.createTempFile(prefix, ".$ext", context.cacheDir)

    fun delete(relative: String?) {
        if (relative.isNullOrBlank()) return
        runCatching { resolve(relative).delete() }
    }

    private fun newFile(dir: String, ext: String): Pair<String, File> {
        val rel = "$ROOT/$dir/${UUID.randomUUID()}.$ext"
        return rel to resolve(rel).apply { parentFile?.mkdirs() }
    }

    companion object {
        const val ROOT = "library"
    }
}
