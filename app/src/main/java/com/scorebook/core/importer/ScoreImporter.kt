package com.scorebook.core.importer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.database.PageEntity
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.render.PageRenderer
import com.scorebook.core.storage.LibraryFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

sealed interface ImportResult {
    data class Success(val scoreId: Long, val title: String) : ImportResult
    data class Duplicate(val scoreId: Long, val title: String) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/** 이미지 악보 만들기 화면에서 넘겨주는 한 장의 정보 */
data class ImagePageInput(val uri: Uri, val rotation: Int = 0)

@Singleton
class ScoreImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: LibraryFiles,
    private val renderer: PageRenderer,
    private val repository: ScoreRepository,
) {

    suspend fun importPdf(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "새 악보"
        val (rel, file) = files.newPdf()
        val hash = try {
            copyWithHash(uri, file)
        } catch (e: Exception) {
            file.delete()
            return@withContext ImportResult.Failure("파일을 읽을 수 없습니다: ${e.message}")
        }

        repository.findByHash(hash)?.let { existing ->
            file.delete()
            return@withContext ImportResult.Duplicate(existing.id, existing.title)
        }

        val sizes = try {
            renderer.readPdfPageSizes(file)
        } catch (e: SecurityException) {
            file.delete()
            return@withContext ImportResult.Failure("암호가 걸린 PDF는 열 수 없습니다")
        } catch (e: Exception) {
            file.delete()
            return@withContext ImportResult.Failure("PDF를 열 수 없습니다")
        }
        if (sizes.isEmpty()) {
            file.delete()
            return@withContext ImportResult.Failure("페이지가 없는 PDF입니다")
        }

        val pages = sizes.mapIndexed { i, size ->
            PageEntity(
                scoreId = 0, position = i, kind = PageSourceKind.PDF.ordinal,
                sourcePath = rel, sourceIndex = i, width = size.width, height = size.height,
            )
        }
        val id = repository.createScore(name, pages, persistOriginal(uri), hash)
        ImportResult.Success(id, name)
    }

    /** 사진 여러 장 → 이미지 악보 (EXIF 회전은 굽고, 사용자 회전은 페이지 메타데이터로) */
    suspend fun importImages(inputs: List<ImagePageInput>, title: String): ImportResult = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext ImportResult.Failure("선택한 사진이 없습니다")
        val pages = ArrayList<PageEntity>()
        val created = ArrayList<String>()
        try {
            for ((index, input) in inputs.withIndex()) {
                val (rel, file) = files.newImage()
                val (w, h) = saveNormalizedImage(input.uri, file)
                created += rel
                pages += PageEntity(
                    scoreId = 0, position = index, kind = PageSourceKind.IMAGE.ordinal,
                    sourcePath = rel, sourceIndex = 0,
                    width = IMAGE_PAGE_WIDTH, height = IMAGE_PAGE_WIDTH * h / w,
                    rotation = ((input.rotation % 360) + 360) % 360,
                )
            }
        } catch (e: Exception) {
            created.forEach(files::delete)
            return@withContext ImportResult.Failure("사진을 불러오지 못했습니다: ${e.message}")
        }
        val name = title.ifBlank { "사진 악보" }
        val id = repository.createScore(name, pages)
        ImportResult.Success(id, name)
    }

    private fun saveNormalizedImage(uri: Uri, target: File): Pair<Int, Int> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) error("이미지 형식을 인식할 수 없습니다")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("이미지를 디코드할 수 없습니다")

        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val longSide = max(decoded.width, decoded.height).toFloat()
        if (longSide > MAX_IMAGE_SIDE) matrix.postScale(MAX_IMAGE_SIDE / longSide, MAX_IMAGE_SIDE / longSide)

        val normalized = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        target.outputStream().use { normalized.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val size = normalized.width to normalized.height
        normalized.recycle()
        return size
    }

    private fun copyWithHash(uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: error("열 수 없는 파일")
        DigestInputStream(input, digest).use { din -> target.outputStream().use { din.copyTo(it, 64 * 1024) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun persistOriginal(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            uri.toString()
        }.getOrNull()
    }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        const val IMAGE_PAGE_WIDTH = 595f
        private const val MAX_IMAGE_SIDE = 3200
    }
}
