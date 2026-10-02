package com.scorebook.core.data

import com.scorebook.core.database.AppDatabase
import com.scorebook.core.database.PracticeSessionEntity
import com.scorebook.core.model.PracticeDay
import com.scorebook.core.model.PracticeTotal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PracticeRepository @Inject constructor(private val db: AppDatabase) {

    suspend fun record(scoreId: Long, startedAt: Long, durationSec: Long) {
        if (durationSec < MIN_SESSION_SEC) return
        db.practiceDao().insert(PracticeSessionEntity(scoreId = scoreId, startedAt = startedAt, durationSec = durationSec))
    }

    /** 최근 [days] 일의 일별 연습 시간 (오래된 날 → 오늘 순서) */
    fun observeDaily(days: Int): Flow<List<PracticeDay>> {
        val starts = dayStarts(days)
        return db.practiceDao().observeSince(starts.first()).map { sessions ->
            starts.map { start ->
                val end = start + DAY_MS
                PracticeDay(start, sessions.filter { it.startedAt in start until end }.sumOf { it.durationSec })
            }
        }
    }

    fun observeTotals(): Flow<List<PracticeTotal>> = db.practiceDao().observeTotals().map { rows ->
        rows.map { PracticeTotal(it.scoreId, it.title, it.seconds, it.sessions) }
    }

    private fun dayStarts(days: Int): List<Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = cal.timeInMillis
        return (days - 1 downTo 0).map { today - it * DAY_MS }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val MIN_SESSION_SEC = 20L
    }
}
