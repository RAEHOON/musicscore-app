package com.scorebook.core.data

import android.content.Context
import android.net.Uri
import com.scorebook.core.database.AppDatabase
import com.scorebook.core.storage.LibraryFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
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
                listOf(files.root, File(context.filesDir, DATASTORE)).forEach { dir ->
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        addFile(zip, f, "files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath)
                    }
                }
            }
        }
    }

    suspend fun restore(source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        // 캐시가 아닌 곳에 풀어야 옮긴 파일이 캐시로 집계되지 않는다
        val staging = File(context.noBackupFilesDir, "restore")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            val stagingRoot = staging.canonicalPath + File.separator
            val input = context.contentResolver.openInputStream(source) ?: error("파일을 열 수 없습니다")
            try {
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
            } catch (e: java.io.IOException) {
                error("백업 파일이 손상되었거나 zip 파일이 아닙니다")
            }
            val stagedDb = File(staging, "db/${AppDatabase.NAME}")
            require(File(staging, MANIFEST).exists() && stagedDb.exists()) { "Hoon's뷰어 백업 파일이 아닙니다" }
            val backupVersion = requireNotNull(sqliteUserVersion(stagedDb)) { "백업 안의 데이터베이스가 손상되었습니다" }
            // 더 새 DB 를 넣으면 앱이 켜질 때마다 죽으므로 여기서 막는다
            require(backupVersion <= AppDatabase.VERSION) { "더 새 버전의 앱에서 만든 백업입니다. 앱을 업데이트한 뒤 복원해 주세요" }

            // 여기까지는 기존 데이터를 건드리지 않았다. 이제부터는 옮겨 두고 교체하며, 실패하면 되돌린다.
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            val stagedFiles = File(staging, "files")
            val old = File(context.noBackupFilesDir, "restore-old").apply { deleteRecursively(); mkdirs() }
            // (현재 위치, 백업에서 올 것, 기존 것을 잠시 둘 곳)
            val swaps = listOf(
                Triple(dbFile, stagedDb, File(old, "db")),
                Triple(files.root, File(stagedFiles, LibraryFiles.ROOT), File(old, LibraryFiles.ROOT)),
                Triple(File(context.filesDir, DATASTORE), File(stagedFiles, DATASTORE), File(old, DATASTORE)),
            )
            db.close()
            listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            val started = ArrayList<Triple<File, File, File>>()
            try {
                for (swap in swaps) {
                    val (live, incoming, keep) = swap
                    if (live.exists()) move(live, keep)
                    started += swap
                    if (incoming.exists()) move(incoming, live)
                }
            } catch (e: Exception) {
                for ((live, _, keep) in started.asReversed()) {
                    runCatching {
                        live.deleteRecursively()
                        if (keep.exists()) move(keep, live)
                    }
                }
                throw RolledBack("${e.message ?: "파일을 옮기지 못했습니다"} (기존 데이터는 그대로입니다)", e)
            }
            old.deleteRecursively()
            Unit
        }.also { staging.deleteRecursively() }
    }

    /** 교체 도중 실패해 기존 데이터로 되돌렸다. DB 를 이미 닫았으므로 앱을 다시 시작해야 한다. */
    class RolledBack(message: String, cause: Throwable) : Exception(message, cause)

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }

    /** SQLite 파일 헤더의 user_version(Room 스키마 버전). SQLite 파일이 아니면 null */
    private fun sqliteUserVersion(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(16).also { raf.readFully(it) }
            if (!magic.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) return@use null
            raf.seek(60)
            raf.readInt()
        }
    }.getOrNull()

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val DATASTORE = "datastore"
    }
}
