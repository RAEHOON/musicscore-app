package com.scorebook.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.ink.geometry.MutableVec
import com.scorebook.core.ink.ItemRenderer
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.BrushKind
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.StaffPaper
import com.scorebook.core.storage.LibraryFiles
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 가상 페이지 + 필기를 실제 PDF 파일로 굽는다.
 *
 * - PDF 페이지는 원본 콘텐츠를 그대로 가져오고(벡터 유지), 필기는 콘텐츠 스트림에 벡터 경로로 추가.
 *   Ink 의 외곽선(outline) 폴리곤을 그대로 채우기 때문에 필압에 따른 굵기 변화까지 화면과 동일.
 * - 이미지 페이지는 JPEG 그대로 임베드(재압축 없음).
 * - 회전/자르기는 /Rotate, /CropBox 로 기록.
 */
@Singleton
class PdfExporter @Inject constructor(private val files: LibraryFiles) {

    suspend fun export(
        pages: List<ScorePage>,
        strokes: List<InkStroke>,
        items: List<AnnotationItem>,
        out: OutputStream,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val strokesByPage = strokes.groupBy { it.pageId }
        val itemsByPage = items.groupBy { it.pageId }
        val sources = HashMap<String, PDDocument>()
        val memory = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)
        PDDocument(memory).use { doc ->
            try {
                pages.forEachIndexed { index, page ->
                    val (pdPage, worldToUser) = addPage(doc, page, sources, memory)
                    page.crop?.let { c ->
                        val p1 = worldToUser.transformPoint(c.left * page.width, c.top * page.height)
                        val p2 = worldToUser.transformPoint(c.right * page.width, c.bottom * page.height)
                        pdPage.cropBox = PDRectangle(
                            min(p1.x, p2.x), min(p1.y, p2.y),
                            kotlin.math.abs(p2.x - p1.x), kotlin.math.abs(p2.y - p1.y),
                        )
                    }
                    val pageStrokes = strokesByPage[page.id].orEmpty()
                    val pageItems = itemsByPage[page.id].orEmpty()
                    if (pageStrokes.isNotEmpty() || pageItems.isNotEmpty()) {
                        PDPageContentStream(doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.saveGraphicsState()
                            cs.transform(worldToUser)
                            pageStrokes.forEach { drawStroke(cs, it) }
                            pageItems.forEach { drawItem(doc, cs, it) }
                            cs.restoreGraphicsState()
                        }
                    }
                    onProgress((index + 1f) / pages.size)
                }
                doc.save(out)
            } finally {
                sources.values.forEach { runCatching { it.close() } }
            }
        }
    }

    private fun addPage(
        doc: PDDocument,
        page: ScorePage,
        sources: MutableMap<String, PDDocument>,
        memory: MemoryUsageSetting,
    ): Pair<PDPage, Matrix> = when (page.kind) {
        PageSourceKind.PDF -> {
            val path = requireNotNull(page.sourcePath)
            val src = sources.getOrPut(path) { PDDocument.load(files.resolve(path), memory) }
            val srcPage = src.getPage(page.sourceIndex)
            val originalRotation = ((srcPage.rotation % 360) + 360) % 360
            val crop = srcPage.cropBox
            val imported = doc.importPage(srcPage)
            imported.rotation = (originalRotation + page.rotation) % 360
            imported to worldToUser(originalRotation, crop)
        }
        PageSourceKind.IMAGE -> {
            val pdPage = PDPage(PDRectangle(page.width, page.height))
            doc.addPage(pdPage)
            val file = files.resolve(requireNotNull(page.sourcePath))
            val image = file.inputStream().use { JPEGFactory.createFromStream(doc, it) }
            PDPageContentStream(doc, pdPage).use { cs -> cs.drawImage(image, 0f, 0f, page.width, page.height) }
            pdPage.rotation = page.rotation
            pdPage to Matrix(1f, 0f, 0f, -1f, 0f, page.height)
        }
        PageSourceKind.STAFF -> {
            val pdPage = PDPage(PDRectangle(StaffPaper.WIDTH, StaffPaper.HEIGHT))
            doc.addPage(pdPage)
            PDPageContentStream(doc, pdPage).use { cs ->
                cs.setLineWidth(StaffPaper.LINE_WIDTH)
                StaffPaper.lines().forEach { (y, x1, x2) ->
                    cs.moveTo(x1, StaffPaper.HEIGHT - y)
                    cs.lineTo(x2, StaffPaper.HEIGHT - y)
                }
                cs.stroke()
            }
            pdPage.rotation = page.rotation
            pdPage to Matrix(1f, 0f, 0f, -1f, 0f, StaffPaper.HEIGHT)
        }
    }

    /**
     * 월드 좌표(PdfRenderer 기준: 원본 /Rotate 적용, 왼쪽 위 원점, y 아래로) → PDF 사용자 공간.
     * Matrix(a, b, c, d, e, f): ux = a*x + c*y + e, uy = b*x + d*y + f
     */
    private fun worldToUser(rotation: Int, crop: PDRectangle): Matrix {
        val llx = crop.lowerLeftX
        val lly = crop.lowerLeftY
        val cw = crop.width
        val ch = crop.height
        return when (rotation) {
            90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
            180 -> Matrix(-1f, 0f, 0f, 1f, llx + cw, lly)
            270 -> Matrix(0f, -1f, -1f, 0f, llx + cw, lly + ch)
            else -> Matrix(1f, 0f, 0f, -1f, llx, lly + ch)
        }
    }

    private fun drawStroke(cs: PDPageContentStream, ink: InkStroke) {
        val color = ink.color
        val gs = PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = Color.alpha(color) / 255f
            if (ink.kind == BrushKind.HIGHLIGHTER) blendMode = BlendMode.MULTIPLY
        }
        cs.setGraphicsStateParameters(gs)
        cs.setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)

        val shape = ink.stroke.shape
        val v = MutableVec()
        for (group in 0 until shape.getRenderGroupCount()) {
            var hasPath = false
            for (outline in 0 until shape.getOutlineCount(group)) {
                val n = shape.getOutlineVertexCount(group, outline)
                if (n < 3) continue
                for (i in 0 until n) {
                    shape.populateOutlinePosition(group, outline, i, v)
                    if (i == 0) cs.moveTo(v.x, v.y) else cs.lineTo(v.x, v.y)
                }
                cs.closePath()
                hasPath = true
            }
            if (hasPath) cs.fill()
        }
    }

    private fun drawItem(doc: PDDocument, cs: PDPageContentStream, item: AnnotationItem) {
        val b = ItemRenderer.bounds(item)
        val scale = 4f
        val w = max(1, ceil(b.width * scale).toInt())
        val h = max(1, ceil(b.height * scale).toInt())
        if (w > 4096 || h > 4096) return
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(scale, scale)
            translate(-b.left, -b.top)
            ItemRenderer.draw(this, item)
        }
        val image = LosslessFactory.createFromImage(doc, bitmap)
        bitmap.recycle()
        // 월드 좌표는 y 가 아래로 증가하므로 이미지를 세로로 뒤집어 배치
        cs.drawImage(image, Matrix(b.width, 0f, 0f, -b.height, b.left, b.top + b.height))
    }
}
