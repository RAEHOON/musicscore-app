package com.scorebook.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * 실제 악보집(사진 스캔 166쪽)으로 곡 나누기 정확도 회귀 테스트.
 *
 * 저작권이 있는 악보 이미지는 저장소에 넣지 않으므로, 페이지 PNG(폭 1000px 흑백)가 있는 폴더를
 * 시스템 속성 `scorebook.samples` 로 지정했을 때만 실행된다.
 *   ./gradlew :app:testDebugUnitTest -Dscorebook.samples=C:/path/to/gray
 */
class ScoreLayoutAnalyzerTest {

    @Test
    fun detectsSongStartsInRealBook() {
        val dir = System.getProperty("scorebook.samples")?.let(::File)
        assumeTrue("샘플 폴더가 지정되지 않음", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.extension == "png" }!!.sortedBy { it.name }

        val analyses = files.map { f ->
            val img = ImageIO.read(f)
            val w = img.width
            val h = img.height
            val gray = IntArray(w * h)
            val raster = img.raster
            for (y in 0 until h) for (x in 0 until w) gray[y * w + x] = raster.getSample(x, y, 0)
            ScoreLayoutAnalyzer.analyze(gray, w, h)
        }
        val starts = ScoreLayoutAnalyzer.songStarts(analyses).map { it + 1 }
        println("곡 시작 페이지: $starts")
        val expected = listOf(1, 2, 17, 28, 41, 49, 55, 64, 71, 79, 82, 84, 86, 96, 103, 114, 124, 134, 146, 151)
        assertEquals(expected, starts)
    }
}
