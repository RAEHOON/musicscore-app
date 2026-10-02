package com.scorebook.core.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import java.text.Normalizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageGeometry
import com.scorebook.core.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 자동으로 찾은 곡 하나 */
class SongCandidate(
    val pageIndex: Int,
    val pageId: Long,
    val name: String,
    /** 큰 제목을 찾아서 */
    val byTitle: Boolean,
    /** 앞 페이지가 끝세로줄로 끝나서 */
    val byPreviousEnd: Boolean,
    /** 제목 부분 미리보기 */
    val preview: Bitmap?,
)

/**
 * 여러 곡이 이어 붙은 악보집을 곡 단위로 나눈다.
 * 모든 처리는 기기 안에서(이미지 분석 + ML Kit 온디바이스 한글 OCR) 이루어진다.
 */
@Singleton
class SongSplitter @Inject constructor(private val renderer: PageRenderer) {

    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    suspend fun detect(pages: List<ScorePage>, onProgress: (done: Int, total: Int) -> Unit): List<SongCandidate> =
        coroutineScope {
            val done = AtomicInteger(0)
            val limit = Semaphore(3)
            val analyses = pages.map { page ->
                async(Dispatchers.Default) {
                    limit.withPermit {
                        ensureActive()
                        val result = analyzePage(page)
                        onProgress(done.incrementAndGet(), pages.size)
                        result
                    }
                }
            }.awaitAll()

            // 악보집 앞쪽 목차가 있으면 OCR 오타 보정에 쓴다 (예: 통행 → 동행)
            val toc = readTableOfContents(pages.take(3))

            ScoreLayoutAnalyzer.songStarts(analyses).map { index ->
                ensureActive()
                val a = analyses[index]
                val prevEnd = index > 0 && analyses[index - 1]?.endsWithFinalBarline == true
                val (ocrName, preview) = readTitle(pages[index], a?.title)
                val name = ocrName?.let { correctWithToc(it, toc) }
                SongCandidate(
                    pageIndex = index,
                    pageId = pages[index].id,
                    name = name ?: "${index + 1}쪽 곡",
                    byTitle = a?.strongTitle == true,
                    byPreviousEnd = prevEnd,
                    preview = preview,
                )
            }
        }

    private fun geometry(page: ScorePage) = PageGeometry(page.width, page.height, page.rotation, PageCrop.FULL)

    private suspend fun analyzePage(page: ScorePage): PageAnalysis? {
        val bmp = renderer.renderSoftware(page, geometry(page), ScoreLayoutAnalyzer.WIDTH) ?: return null
        return withContext(Dispatchers.Default) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (i in px.indices) {
                val c = px[i]
                px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            runCatching { ScoreLayoutAnalyzer.analyze(px, w, h) }.getOrNull()
        }
    }

    /**
     * 제목 영역을 고해상도로 다시 그려 OCR.
     * 제목 줄 → (실패 시) 넓힌 영역 → 페이지 위쪽 35% 순서로 시도해 처음 읽힌 글자를 쓴다.
     */
    private suspend fun readTitle(page: ScorePage, title: TitleLine?): Pair<String?, Bitmap?> {
        val full = renderer.renderSoftware(page, geometry(page), OCR_WIDTH) ?: return null to null
        val w = full.width
        val h = full.height
        fun rectOf(l: Float, t: Float, r: Float, b: Float) = Rect(
            (l * w).roundToInt().coerceIn(0, w), (t * h).roundToInt().coerceIn(0, h),
            (r * w).roundToInt().coerceIn(0, w), (b * h).roundToInt().coerceIn(0, h),
        )
        // (영역, 마지막 수단 여부). 제목 줄 상자는 끝 글자가 잘릴 수 있어 넉넉히 넓힌 영역부터
        val attempts = buildList {
            if (title != null) {
                add(rectOf(title.left - 0.15f, title.top - 0.03f, title.right + 0.15f, title.bottom + 0.03f) to false)
            }
            add(rectOf(0f, 0f, 1f, 0.35f) to true)
        }.filter { it.first.width() >= 8 && it.first.height() >= 8 }

        var name: String? = null
        var preview: Bitmap? = null
        for ((rect, lastResort) in attempts) {
            val crop = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
            if (preview == null) preview = scaledPreview(crop)
            name = ocrName(crop, lastResort)
            if (name == null) {
                // 뒷면 비침·회색 배경 때문에 못 읽는 경우: 흑백으로 정리하고 여백을 붙여서, 크기를 바꿔 가며 다시
                val clean = cleanForOcr(crop)
                for (scale in floatArrayOf(1f, 1.6f, 2.2f)) {
                    val variant = if (scale == 1f) clean else Bitmap.createScaledBitmap(
                        clean, max(8, (clean.width * scale).roundToInt()), max(8, (clean.height * scale).roundToInt()), true,
                    )
                    name = ocrName(variant, lastResort)
                    if (variant !== clean) variant.recycle()
                    if (name != null) break
                }
                clean.recycle()
            }
            if (crop !== full) crop.recycle()
            if (name != null) break
        }
        full.recycle()
        return name to preview
    }

    private suspend fun ocrName(bitmap: Bitmap, strict: Boolean): String? =
        runCatching { recognize(bitmap) }.getOrNull()?.let { pickTitle(it, strict) }

    /** 회색 배경/비침 제거: 밝기 150 기준 흑백 + 사방 24px 흰 여백 */
    private fun cleanForOcr(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            px[i] = if (lum < 150) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val pad = 24
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFFFFFFFF.toInt())
        out.setPixels(px, 0, w, pad, pad, w, h)
        return out
    }

    private fun scaledPreview(src: Bitmap): Bitmap {
        val width = min(src.width, 720)
        return Bitmap.createScaledBitmap(src, width, max(1, src.height * width / src.width), true)
    }

    /** 앞쪽 페이지에서 "1. 곡 이름(편곡자)" 형태의 줄이 3개 이상 있으면 목차로 본다 */
    private suspend fun readTableOfContents(pages: List<ScorePage>): List<String> {
        val entry = Regex("^\\s*(\\d{1,3})\\s*[.)·]\\s*(.+)$")
        for (page in pages) {
            val bmp = renderer.renderSoftware(page, geometry(page), TOC_WIDTH) ?: continue
            val text = runCatching { recognize(bmp) }.getOrNull()
            bmp.recycle()
            val titles = text?.textBlocks?.flatMap { it.lines }?.mapNotNull { line ->
                entry.find(line.text)?.groupValues?.get(2)
                    ?.replace(Regex("\\s*[(\\[].*$"), "") // 괄호 뒤 편곡자·작곡자 정보 제거
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            if (titles.size >= 3) return titles
        }
        return emptyList()
    }

    /**
     * 목차로 한글 OCR 오타를 바로잡는다 (흘/홀, 동/통 처럼 비슷한 글자).
     * - 라틴 제목은 제목 페이지 OCR 이 정확하고 목차에 오타가 있을 수도 있어(Hear/Here) 건드리지 않는다.
     * - 글자만 목차 것을 쓰고 띄어쓰기는 제목 페이지 것을 유지한다.
     */
    private fun correctWithToc(name: String, toc: List<String>): String {
        if (toc.isEmpty() || name.none { it in '가'..'힣' }) return name
        fun compact(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
        val a = compact(name)
        if (a.isEmpty()) return name
        var best: String? = null
        var bestSim = 0f
        for (entry in toc) {
            if (entry.none { it in '가'..'힣' }) continue
            val b = compact(entry)
            if (b.isEmpty()) continue
            val sim = 1f - levenshtein(a, b).toFloat() / max(a.length, b.length)
            if (sim > bestSim) {
                bestSim = sim
                best = entry
            }
        }
        if (best == null || bestSim < 0.5f || bestSim >= 1f) return name
        val letters = compact(best)
        if (letters.length != a.length) return best
        // 같은 글자 수면 제목 페이지의 띄어쓰기를 살려 글자만 교체
        val out = StringBuilder()
        var k = 0
        for (c in name) {
            if (c.isLetterOrDigit() && k < letters.length) out.append(letters[k++]) else out.append(c)
        }
        return out.toString()
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.cancel(it) }
    }

    /** 가장 큰 글자 줄(과 같은 높이로 나란히 있는 줄)을 제목으로. 편성·저작권 문구나 잡음 줄은 제외 */
    private fun pickTitle(text: Text, strict: Boolean): String? {
        val lines = text.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null && plausibleTitle(it.text, strict) }
        if (lines.isEmpty()) return null
        val tallest = lines.maxOf { it.boundingBox!!.height() }
        val ref = lines.first { it.boundingBox!!.height() == tallest }.boundingBox!!
        val same = lines.filter {
            val b = it.boundingBox!!
            b.height() >= tallest * 0.75f && b.centerY() in ref.top..ref.bottom
        }.sortedBy { it.boundingBox!!.left }
        return cleanTitle(same.joinToString(" ") { it.text.trim() })?.takeIf { plausibleTitle(it, strict) }
    }

    private val boilerplate = Regex(
        "(?i)\\b(SATB|SSAA|TTBB|voices?|accompaniment|arranged|arr\\.|copyright|words and music|music by|words by|a cappella|unison)\\b",
    )

    private val stopWords = setOf("the", "and", "for", "of", "by", "with", "in", "a", "an", "to", "op", "no")

    /** 제목다운 글자인지: 한글 2자 이상, 또는 모음이 있는 라틴 글자 3자 이상. 편성/저작권 문구는 제외 */
    private fun plausibleTitle(raw: String, strict: Boolean = false): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || boilerplate.containsMatchIn(t)) return false
        val hangul = t.count { it in '가'..'힣' }
        val latin = t.count { it.isLetter() && it.code < 0x0250 }
        val vowel = t.any { it.lowercaseChar() in "aeiouy" }
        val words = t.lowercase().split(Regex("[^a-z가-힣]+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.all { it in stopWords }) return false
        // 제목을 못 찾아 페이지 위쪽 전체를 읽은 경우엔 더 엄격하게(두 단어 이상)
        if (strict && hangul < 2 && words.size < 2) return false
        return hangul >= 2 || (hangul == 0 && latin >= 3 && vowel)
    }

    /** OCR 잡음 정리: 짝 없는 괄호, 라틴 문자에 붙은 엉뚱한 악센트(ę→e) 제거. 한글은 그대로 둔다 */
    private fun cleanTitle(raw: String): String? {
        val sb = StringBuilder()
        for (c in raw) {
            if (c.code in 0x00C0..0x024F) {
                sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), ""))
            } else {
                sb.append(c)
            }
        }
        var t = sb.toString().replace(Regex("\\s+"), " ").trim()
        val pairs = listOf('(' to ')', '[' to ']', '{' to '}', '<' to '>')
        for ((open, close) in pairs) {
            if (t.count { it == open } != t.count { it == close }) t = t.replace(open.toString(), "").replace(close.toString(), "")
        }
        t = t.trim(' ', '|', '-', '_', '.', ',', '"', '\'', '·')
        return t.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val OCR_WIDTH = 1600
        private const val TOC_WIDTH = 2000
    }
}
