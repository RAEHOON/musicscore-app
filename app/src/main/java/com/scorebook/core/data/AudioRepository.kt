package com.scorebook.core.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.scorebook.core.database.AppDatabase
import com.scorebook.core.database.AudioEntity
import com.scorebook.core.model.AudioClip
import com.scorebook.core.model.AudioKind
import com.scorebook.core.storage.LibraryFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: LibraryFiles,
) {
    fun observeForScore(scoreId: Long): Flow<List<AudioClip>> =
        db.audioDao().observeForScore(scoreId).map { list -> list.map { it.toModel() } }

    /** 외부 음원(mp3, m4a 등)을 앱 저장소로 복사해 악보에 첨부 */
    suspend fun importAudio(scoreId: Long, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "음원"
            val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
            val (rel, file) = files.newAudio(ext)
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: error("파일을 열 수 없습니다")
            db.audioDao().insert(
                AudioEntity(
                    scoreId = scoreId,
                    title = name.substringBeforeLast('.'),
                    path = rel,
                    kind = AudioKind.ATTACHED.code,
                    durationMs = duration(file.absolutePath),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Unit
        }
    }

    suspend fun addRecording(scoreId: Long, relativePath: String, title: String) = withContext(Dispatchers.IO) {
        db.audioDao().insert(
            AudioEntity(
                scoreId = scoreId,
                title = title,
                path = relativePath,
                kind = AudioKind.RECORDING.code,
                durationMs = duration(files.resolve(relativePath).absolutePath),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: Long) {
        val entity = db.audioDao().get(id) ?: return
        db.audioDao().delete(id)
        files.delete(entity.path)
    }

    private fun duration(path: String): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(path)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)
}
