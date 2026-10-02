package com.scorebook.core.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.ItemKind
import com.scorebook.core.model.WorldRect

data class Stamp(val code: String, val label: String, val category: String)

/** 음악 기호 스탬프 목록. 시스템 폰트 의존 없이 직접 그려서 기기/PDF 어디서나 동일하게 보인다. */
object Stamps {
    /** 지시어 카테고리: 이 카테고리의 스탬프는 기호가 아니라 글자이므로 선택 화면에서 넓은 칩으로 보여준다 */
    val TEXT_CATEGORIES = setOf(
        "빠르기", "빠르기 변화", "셈여림·표현 변화", "나타냄말", "반복·구조", "현악기 주법", "건반·페달", "성부·편성", "기타",
    )

    private fun MutableList<Stamp>.texts(category: String, vararg words: String) =
        words.forEach { add(Stamp("text:$it", it, category)) }

    private fun MutableList<Stamp>.tempos(vararg words: String) =
        words.forEach { add(Stamp("tempo:$it", it, "빠르기")) }

    val all: List<Stamp> = buildList {
        listOf("ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "sfz", "sf", "sffz", "fz", "rfz", "fp", "sfp").forEach {
            add(Stamp("dyn:$it", it, "셈여림"))
        }
        add(Stamp("hair:cresc", "<", "셈여림"))
        add(Stamp("hair:dim", ">", "셈여림"))
        (0..5).forEach { add(Stamp("fing:$it", "$it", "운지")) }
        add(Stamp("art:accent", "악센트", "아티큘레이션"))
        add(Stamp("art:staccato", "스타카토", "아티큘레이션"))
        add(Stamp("art:tenuto", "테누토", "아티큘레이션"))
        add(Stamp("art:marcato", "마르카토", "아티큘레이션"))
        add(Stamp("art:fermata", "페르마타", "아티큘레이션"))
        add(Stamp("breath:comma", "브레스", "호흡"))
        add(Stamp("breath:caesura", "카이저", "호흡"))
        add(Stamp("bow:down", "다운보우", "보잉"))
        add(Stamp("bow:up", "업보우", "보잉"))
        add(Stamp("sym:segno", "세뇨", "기호"))
        add(Stamp("sym:coda", "코다", "기호"))
        add(Stamp("sym:glasses", "주의", "기호"))

        // 빠르기 (느림 → 빠름)
        tempos(
            "Grave", "Largo", "Larghetto", "Lento", "Adagio", "Adagietto", "Andante", "Andantino",
            "Moderato", "Allegretto", "Allegro", "Allegro moderato", "Allegro assai", "Vivace", "Vivacissimo",
            "Presto", "Prestissimo",
        )
        // 빠르기 변화
        texts(
            "빠르기 변화",
            "rit.", "ritard.", "rall.", "allarg.", "accel.", "string.", "stretto", "a tempo", "Tempo I",
            "tempo rubato", "rubato", "meno mosso", "più mosso", "l'istesso tempo", "calando", "ten.",
            "poco a poco", "senza tempo", "a piacere", "ad lib.", "rit. molto",
        )
        // 셈여림·표현 변화
        texts(
            "셈여림·표현 변화",
            "cresc.", "crescendo", "dim.", "diminuendo", "decresc.", "morendo", "smorz.", "perdendosi",
            "subito p", "subito f", "sub. p", "poco", "molto", "più", "meno", "sempre", "poco a poco cresc.",
        )
        // 나타냄말 (표정·성격)
        texts(
            "나타냄말",
            "dolce", "espress.", "con espressione", "cantabile", "legato", "leggiero", "staccato", "marcato",
            "sostenuto", "tenuto", "agitato", "animato", "appassionato", "con brio", "con fuoco", "con moto",
            "grazioso", "maestoso", "pesante", "sotto voce", "mezza voce", "tranquillo", "giocoso", "scherzando",
            "mesto", "semplice", "affettuoso", "amoroso", "brillante", "calmato", "deciso", "delicato",
            "energico", "furioso", "lamentoso", "misterioso", "nobilmente", "risoluto", "rustico", "spiritoso",
        )
        // 반복·구조
        texts(
            "반복·구조",
            "D.C.", "D.S.", "D.C. al Fine", "D.C. al Coda", "D.S. al Fine", "D.S. al Coda", "Fine", "To Coda",
            "Coda", "V.S.", "attacca", "segue", "simile", "sim.", "1.", "2.", "3.",
        )
        // 현악기 주법
        texts(
            "현악기 주법",
            "arco", "pizz.", "col legno", "sul pont.", "sul tasto", "ord.", "div.", "unis.",
            "con sord.", "senza sord.", "trem.", "harm.", "spicc.", "détaché", "martelé", "sul G", "sul D",
        )
        // 건반·페달
        texts(
            "건반·페달",
            "Ped.", "*", "una corda", "tre corde", "m.d.", "m.s.", "R.H.", "L.H.", "8va", "8vb", "15ma", "loco",
            "senza Ped.", "con Ped.", "gliss.",
        )
        // 성부·편성
        texts("성부·편성", "tutti", "solo", "soli", "a2", "a3", "div. a2", "unis.", "S.", "A.", "T.", "B.", "1st", "2nd")
        // 기타 연습 메모용
        texts("기타", "!", "?", "↑", "↓", "watch", "count!", "breathe", "slow", "listen", "tune", "rest")
    }.distinctBy { it.code }

    val categories: List<String> = all.map { it.category }.distinct()

    fun find(code: String): Stamp? = all.firstOrNull { it.code == code }

    fun isTextual(code: String): Boolean = code.startsWith("text:") || code.startsWith("tempo:")
}

object ItemRenderer {

    private val serifBoldItalic = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    private val serifItalic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private fun textPaint(color: Int, size: Float, typeface: Typeface = Typeface.DEFAULT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** canvas 는 월드 좌표계(pt) 상태여야 한다. */
    fun draw(canvas: Canvas, item: AnnotationItem) {
        when (item.kind) {
            ItemKind.TEXT -> drawText(canvas, item.text, item.x, item.y, item.size, item.color, Typeface.DEFAULT)
            ItemKind.STAMP -> drawStamp(canvas, item.text, item.x, item.y, item.size, item.color)
        }
    }

    fun bounds(item: AnnotationItem): WorldRect {
        val r = when (item.kind) {
            ItemKind.TEXT -> textBounds(item.text, item.size, Typeface.DEFAULT)
            ItemKind.STAMP -> stampBounds(item.text, item.size)
        }
        return WorldRect(item.x + r.left, item.y + r.top, item.x + r.right, item.y + r.bottom)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface) {
        val paint = textPaint(color, size, tf)
        val lines = text.split('\n')
        val lineHeight = size * 1.2f
        val totalHeight = lineHeight * lines.size
        val fm = paint.fontMetrics
        var baseline = y - totalHeight / 2 + (lineHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun textBounds(text: String, size: Float, tf: Typeface): RectF {
        val paint = textPaint(0, size, tf)
        val lines = text.split('\n')
        val w = (lines.maxOfOrNull { paint.measureText(it) } ?: 0f).coerceAtLeast(size * 0.6f)
        val h = size * 1.2f * lines.size
        return RectF(-w / 2, -h / 2, w / 2, h / 2)
    }

    private fun stampBounds(code: String, size: Float): RectF {
        val (type, value) = code.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (type) {
            "dyn" -> textBounds(value, size, serifBoldItalic)
            "text" -> textBounds(value, size * 0.8f, serifItalic)
            "tempo" -> textBounds(value, size * 0.9f, serifBold)
            "fing" -> textBounds(value, size * 0.8f, sansBold)
            "hair" -> RectF(-size * 1.4f, -size * 0.4f, size * 1.4f, size * 0.4f)
            else -> RectF(-size * 0.55f, -size * 0.55f, size * 0.55f, size * 0.55f)
        }
    }

    fun drawStamp(canvas: Canvas, code: String, x: Float, y: Float, size: Float, color: Int) {
        val (type, value) = code.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val s = size
        val line = strokePaint(color, s * 0.08f)
        when (type) {
            "dyn" -> drawText(canvas, value, x, y, s, color, serifBoldItalic)
            "text" -> drawText(canvas, value, x, y, s * 0.8f, color, serifItalic)
            "tempo" -> drawText(canvas, value, x, y, s * 0.9f, color, serifBold)
            "fing" -> drawText(canvas, value, x, y, s * 0.8f, color, sansBold)
            "hair" -> {
                val w = s * 1.4f
                val h = s * 0.35f
                val path = Path()
                if (value == "cresc") {
                    path.moveTo(x + w, y - h); path.lineTo(x - w, y); path.lineTo(x + w, y + h)
                } else {
                    path.moveTo(x - w, y - h); path.lineTo(x + w, y); path.lineTo(x - w, y + h)
                }
                canvas.drawPath(path, line)
            }
            "art" -> when (value) {
                "accent" -> {
                    val p = Path().apply {
                        moveTo(x - s * 0.45f, y - s * 0.25f); lineTo(x + s * 0.45f, y); lineTo(x - s * 0.45f, y + s * 0.25f)
                    }
                    canvas.drawPath(p, line)
                }
                "staccato" -> canvas.drawCircle(x, y, s * 0.12f, fillPaint(color))
                "tenuto" -> canvas.drawLine(x - s * 0.4f, y, x + s * 0.4f, y, strokePaint(color, s * 0.1f))
                "marcato" -> {
                    val p = Path().apply {
                        moveTo(x - s * 0.3f, y + s * 0.35f); lineTo(x, y - s * 0.35f); lineTo(x + s * 0.3f, y + s * 0.35f)
                    }
                    canvas.drawPath(p, strokePaint(color, s * 0.1f))
                }
                "fermata" -> {
                    val oval = RectF(x - s * 0.5f, y - s * 0.4f, x + s * 0.5f, y + s * 0.5f)
                    canvas.drawArc(oval, 180f, 180f, false, strokePaint(color, s * 0.09f))
                    canvas.drawCircle(x, y + s * 0.15f, s * 0.09f, fillPaint(color))
                }
            }
            "breath" -> when (value) {
                "comma" -> drawText(canvas, ",", x, y - s * 0.2f, s * 1.4f, color, Typeface.create(Typeface.SERIF, Typeface.BOLD))
                "caesura" -> {
                    val p = strokePaint(color, s * 0.09f)
                    canvas.drawLine(x - s * 0.35f, y + s * 0.45f, x - s * 0.05f, y - s * 0.45f, p)
                    canvas.drawLine(x + s * 0.05f, y + s * 0.45f, x + s * 0.35f, y - s * 0.45f, p)
                }
            }
            "bow" -> when (value) {
                "down" -> {
                    val w = s * 0.45f
                    val top = y - s * 0.35f
                    canvas.drawRect(x - w, top, x + w, top + s * 0.16f, fillPaint(color))
                    val p = strokePaint(color, s * 0.08f)
                    canvas.drawLine(x - w + s * 0.04f, top, x - w + s * 0.04f, y + s * 0.4f, p)
                    canvas.drawLine(x + w - s * 0.04f, top, x + w - s * 0.04f, y + s * 0.4f, p)
                }
                "up" -> {
                    val p = Path().apply {
                        moveTo(x - s * 0.35f, y - s * 0.45f); lineTo(x, y + s * 0.45f); lineTo(x + s * 0.35f, y - s * 0.45f)
                    }
                    canvas.drawPath(p, strokePaint(color, s * 0.08f))
                }
            }
            "sym" -> when (value) {
                "coda" -> {
                    val p = strokePaint(color, s * 0.07f)
                    canvas.drawOval(RectF(x - s * 0.32f, y - s * 0.4f, x + s * 0.32f, y + s * 0.4f), p)
                    canvas.drawLine(x - s * 0.55f, y, x + s * 0.55f, y, p)
                    canvas.drawLine(x, y - s * 0.55f, x, y + s * 0.55f, p)
                }
                "segno" -> {
                    drawText(canvas, "S", x, y, s * 1.1f, color, serifBoldItalic)
                    val p = strokePaint(color, s * 0.07f)
                    canvas.drawLine(x - s * 0.4f, y + s * 0.45f, x + s * 0.4f, y - s * 0.45f, p)
                    canvas.drawCircle(x - s * 0.35f, y + s * 0.05f, s * 0.07f, fillPaint(color))
                    canvas.drawCircle(x + s * 0.35f, y - s * 0.05f, s * 0.07f, fillPaint(color))
                }
                "glasses" -> {
                    val p = strokePaint(color, s * 0.08f)
                    val r = s * 0.22f
                    canvas.drawCircle(x - s * 0.3f, y + s * 0.05f, r, p)
                    canvas.drawCircle(x + s * 0.3f, y + s * 0.05f, r, p)
                    canvas.drawArc(RectF(x - s * 0.1f, y - s * 0.12f, x + s * 0.1f, y + s * 0.08f), 200f, 140f, false, p)
                    canvas.drawLine(x - s * 0.52f, y, x - s * 0.62f, y - s * 0.25f, p)
                    canvas.drawLine(x + s * 0.52f, y, x + s * 0.62f, y - s * 0.25f, p)
                }
            }
        }
    }
}
