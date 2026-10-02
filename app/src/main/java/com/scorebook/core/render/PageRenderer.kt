package com.scorebook.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.SizeF
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.model.ScorePage
import com.scorebook.core.storage.LibraryFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 페이지 → 비트맵 렌더링 파이프라인.
 *
 * - PdfRenderer 는 스레드 안전하지 않고 한 번에 한 페이지만 열 수 있으므로 파일별 [Mutex] 로 직렬화.
 * - 열린 PDF 는 LRU 로 최대 [MAX_OPEN_PDFS] 개 유지(파일 열기 비용 절약).
 * - 결과는 HARDWARE 비트맵으로 변환해 GPU 메모리에 두고 LRU 캐시 → 자바 힙 압박 없이 빠른 페이지 넘김.
 */
@Singleton
class PageRenderer @Inject constructor(private val files: LibraryFiles) {

    private val renderDispatcher = Dispatchers.IO.limitedParallelism(2)

    private class OpenPdf(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        val mutex = Mutex()
        fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    private val openPdfs = LinkedHashMap<String, OpenPdf>(8, 0.75f, true)
    private val openLock = Mutex()

    private val cache = object : LruCache<String, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val contentBoundsCache = ConcurrentHashMap<String, PageCrop>()
    private val noContent = PageCrop.FULL

    fun cached(page: ScorePage, geometry: PageGeometry, widthPx: Int): Bitmap? =
        cache.get(key(page, geometry, widthPx))

    /** 표시 크기(widthPx) 로 페이지 전체를 렌더링. 캐시 히트 시 즉시 반환. */
    suspend fun render(page: ScorePage, geometry: PageGeometry, widthPx: Int): Bitmap? {
        if (widthPx <= 0) return null
        val key = key(page, geometry, widthPx)
        cache.get(key)?.let { return it }
        return withContext(renderDispatcher) {
            cache.get(key)?.let { return@withContext it }
            val heightPx = (widthPx / geometry.aspectRatio).roundToInt().coerceAtLeast(1)
            val software = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            software.eraseColor(Color.WHITE)
            val ok = runCatching { drawInto(page, software, geometry.worldToLocal(widthPx.toFloat())) }.isSuccess
            if (!ok) {
                software.recycle()
                return@withContext null
            }
            val result = toHardware(software)
            cache.put(key, result)
            result
        }
    }

    /**
     * 확대 시 선명도를 위한 부분 렌더링.
     * [fullWidthPx] 폭으로 그린 페이지에서 [region](로컬 px) 만 잘라 고해상도로 그린다.
     */
    suspend fun renderRegion(
        page: ScorePage,
        geometry: PageGeometry,
        fullWidthPx: Float,
        region: RectF,
    ): Bitmap? = withContext(renderDispatcher) {
        val w = region.width().roundToInt()
        val h = region.height().roundToInt()
        if (w <= 0 || h <= 0 || w > MAX_REGION_PX || h > MAX_REGION_PX) return@withContext null
        val software = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        software.eraseColor(Color.WHITE)
        val m = geometry.worldToLocal(fullWidthPx)
        m.postTranslate(-region.left, -region.top)
        val ok = runCatching { drawInto(page, software, m) }.isSuccess
        if (!ok) {
            software.recycle()
            null
        } else {
            toHardware(software)
        }
    }

    /** 레이아웃 분석/OCR 용: 픽셀을 읽을 수 있는 소프트웨어 비트맵 (캐시하지 않음) */
    suspend fun renderSoftware(page: ScorePage, geometry: PageGeometry, widthPx: Int): Bitmap? =
        withContext(renderDispatcher) {
            val h = (widthPx / geometry.aspectRatio).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            if (runCatching { drawInto(page, bmp, geometry.worldToLocal(widthPx.toFloat())) }.isSuccess) {
                bmp
            } else {
                bmp.recycle()
                null
            }
        }

    /** 여백 자동 자르기용: 실제 음표가 있는 영역(월드 비율) */
    suspend fun contentBounds(page: ScorePage): PageCrop? {
        val cacheKey = sourceKey(page)
        contentBoundsCache[cacheKey]?.let { return it.takeIf { b -> b !== noContent } }
        return withContext(renderDispatcher) {
            val geometry = PageGeometry(page.width, page.height, 0, PageCrop.FULL)
            val w = 320
            val h = (w / geometry.aspectRatio).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            runCatching { drawInto(page, bmp, geometry.worldToLocal(w.toFloat())) }
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val c = pixels[row + x]
                    val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    if (lum < 200) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val result = if (maxX < 0 || (maxX - minX) < w * 0.2f) {
                noContent
            } else {
                val pad = 0.015f
                PageCrop(
                    left = max(0f, minX.toFloat() / w - pad),
                    top = max(0f, minY.toFloat() / h - pad),
                    right = min(1f, (maxX + 1).toFloat() / w + pad),
                    bottom = min(1f, (maxY + 1).toFloat() / h + pad),
                )
            }
            contentBoundsCache[cacheKey] = result
            result.takeIf { it !== noContent }
        }
    }

    /** 라이브러리 썸네일(JPEG) 생성 */
    suspend fun writeThumbnail(page: ScorePage, target: File, widthPx: Int = 420): Boolean =
        withContext(renderDispatcher) {
            val geometry = PageGeometry.of(page)
            val h = (widthPx / geometry.aspectRatio).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val ok = runCatching { drawInto(page, bmp, geometry.worldToLocal(widthPx.toFloat())) }.isSuccess
            if (ok) {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
            bmp.recycle()
            ok
        }

    /** PDF 파일의 페이지 크기 목록(포인트). 암호화 등으로 열 수 없으면 예외. */
    fun readPdfPageSizes(file: File): List<SizeF> {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                return (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) }
                }
            }
        }
    }

    fun clearMemory() {
        cache.evictAll()
    }

    suspend fun closeAll() {
        openLock.withLock {
            openPdfs.values.forEach { pdf -> pdf.mutex.withLock { pdf.close() } }
            openPdfs.clear()
        }
        cache.evictAll()
    }

    // ---------------------------------------------------------------------

    private suspend fun drawInto(page: ScorePage, bitmap: Bitmap, worldToBitmap: Matrix) {
        when (page.kind) {
            PageSourceKind.PDF -> drawPdf(page, bitmap, worldToBitmap)
            PageSourceKind.IMAGE -> drawImage(page, bitmap, worldToBitmap)
            PageSourceKind.STAFF -> drawStaff(bitmap, worldToBitmap)
        }
    }

    private suspend fun drawPdf(page: ScorePage, bitmap: Bitmap, matrix: Matrix) {
        val path = page.sourcePath ?: return
        val pdf = openPdf(path)
        pdf.mutex.withLock {
            pdf.renderer.openPage(page.sourceIndex).use { p ->
                p.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    private suspend fun openPdf(relative: String): OpenPdf = openLock.withLock {
        openPdfs[relative]?.let { return@withLock it }
        val file = files.resolve(relative)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = OpenPdf(pfd, PdfRenderer(pfd))
        openPdfs[relative] = pdf
        if (openPdfs.size > MAX_OPEN_PDFS) {
            val eldestKey = openPdfs.keys.first()
            openPdfs.remove(eldestKey)?.let { old -> old.mutex.withLock { old.close() } }
        }
        pdf
    }

    private fun drawImage(page: ScorePage, bitmap: Bitmap, worldToBitmap: Matrix) {
        val path = page.sourcePath ?: return
        val file = files.resolve(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val iw = bounds.outWidth
        val ih = bounds.outHeight
        if (iw <= 0 || ih <= 0) return

        val imageToWorld = page.width / iw
        val imageToBitmap = Matrix(worldToBitmap).apply { preScale(imageToWorld, imageToWorld) }
        val values = FloatArray(9).also { imageToBitmap.getValues(it) }
        val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])

        // 출력 비트맵에 실제로 보이는 원본 이미지 영역만 디코드
        val inverse = Matrix().also { imageToBitmap.invert(it) }
        val visible = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        inverse.mapRect(visible)
        val rect = Rect(
            max(0, floor(visible.left).toInt()),
            max(0, floor(visible.top).toInt()),
            min(iw, ceil(visible.right).toInt()),
            min(ih, ceil(visible.bottom).toInt()),
        )
        if (rect.isEmpty) return

        var sample = 1
        while (sample * 2 * scale <= 1f) sample *= 2

        val decoder = if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(file.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }
        try {
            val region = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val m = Matrix(imageToBitmap).apply {
                preTranslate(rect.left.toFloat(), rect.top.toFloat())
                preScale(rect.width().toFloat() / region.width, rect.height().toFloat() / region.height)
            }
            Canvas(bitmap).drawBitmap(region, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            region.recycle()
        } finally {
            decoder.recycle()
        }
    }

    private fun drawStaff(bitmap: Bitmap, worldToBitmap: Matrix) {
        val canvas = Canvas(bitmap)
        canvas.concat(worldToBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = StaffPaper.LINE_WIDTH
            style = Paint.Style.STROKE
        }
        StaffPaper.lines().forEach { (y, x1, x2) -> canvas.drawLine(x1, y, x2, y, paint) }
    }

    private fun toHardware(software: Bitmap): Bitmap {
        val hw = runCatching { software.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
        return if (hw != null) {
            software.recycle()
            hw
        } else {
            software
        }
    }

    private fun sourceKey(page: ScorePage) = "${page.kind}|${page.sourcePath}|${page.sourceIndex}"

    private fun key(page: ScorePage, g: PageGeometry, widthPx: Int) =
        "${sourceKey(page)}|${g.rotation}|${g.crop}|$widthPx"

    companion object {
        private const val MAX_OPEN_PDFS = 4
        private const val MAX_REGION_PX = 4096

        private fun cacheBudgetBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return min(max / 3, 256L * 1024 * 1024).toInt()
        }
    }
}
