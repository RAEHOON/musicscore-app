package com.scorebook.core.util

/** 한글 초성 검색 지원 ("ㄱㅇㄹ" → "고양이 랩소디") */
object KoreanSearch {
    private val CHOSUNG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )

    private fun isChosung(c: Char) = c in CHOSUNG

    private fun chosungOf(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c in '가'..'힣') append(CHOSUNG[(c - '가') / (21 * 28)]) else append(c)
        }
    }

    fun matches(text: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val t = text.lowercase()
        if (t.contains(q)) return true
        return q.any(::isChosung) && chosungOf(t).replace(" ", "").contains(q.replace(" ", ""))
    }
}
