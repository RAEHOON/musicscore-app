package com.scorebook.core.data

import android.content.Context
import android.net.Uri
import com.scorebook.core.database.AppDatabase
import com.scorebook.core.storage.LibraryFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 서버 없는 백업: DB 파일 + 악보/이미지/음원 + 설정을 zip 하나로.
 * 복원은 임시 폴더에 먼저 풀어 검증한 뒤 교체하고, 앱을 재시작한다.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: LibraryFiles,
) {
    suspend fun export(target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("파일을 만들 수 없습니다")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write("""{"app":"scorebook","version":1,"createdAt":${System.currentTimeMillis()}}""".toByteArray())
                zip.closeEntry()

                addFile(zip, context.getDatabasePath(AppDatabase.NAME), "db/${AppDatabase.NAME}")
                listOf(files.root, File(context.filesDir, "datastore")).forEach { dir ->
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        addFile(zip, f, "files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath)
                    }
                }
            }
        }
    }

    suspend fun restore(source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
            val stagingRoot = staging.canonicalPath + File.separator
            val input = context.contentResolver.openInputStream(source) ?: error("파일을 열 수 없습니다")
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name)
                    // zip slip 방지
                    require(target.canonicalPath.startsWith(stagingRoot)) { "잘못된 백업 파일입니다" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
            val stagedDb = File(staging, "db/${AppDatabase.NAME}")
            require(File(staging, MANIFEST).exists() && stagedDb.exists()) { "Hoon's뷰어 백업 파일이 아닙니다" }

            db.close()
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            stagedDb.copyTo(dbFile, overwrite = true)

            files.root.deleteRecursively()
            File(context.filesDir, "datastore").deleteRecursively()
            File(staging, "files").takeIf { it.exists() }?.copyRecursively(context.filesDir, overwrite = true)
            staging.deleteRecursively()
            Unit
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    companion object {
        private const val MANIFEST = "manifest.json"
    }
}
