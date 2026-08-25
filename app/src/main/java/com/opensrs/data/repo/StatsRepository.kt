package com.opensrs.data.repo

import com.opensrs.data.db.FlashcardDao
import com.opensrs.data.db.WordDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Snapshot of study statistics for the Stats screen. */
data class StudyStats(
    val newCount: Int,
    val learningCount: Int,
    val graduatedCount: Int,
    val matureCount: Int,
    val totalReviews: Int,
    val totalLapses: Int,
    /** Due-count for today + the next 6 days (index 0 = today). */
    val forecast: List<Int>,
)

class StatsRepository(
    private val wordDao: WordDao,
    private val cardDao: FlashcardDao,
) {
    suspend fun load(): StudyStats = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        // "Today" bucket starts at the current day boundary (local time approximation: UTC).
        val todayStart = now - (now % dayMs)

        val forecast = (0..6).map { d ->
            cardDao.dueCountBetween(todayStart + d * dayMs, todayStart + (d + 1) * dayMs)
        }
        StudyStats(
            newCount = wordDao.count(), // NEW cards are implicit; all unseen words count
            learningCount = cardDao.all().count { it.state == com.opensrs.data.db.CardState.LEARNING },
            graduatedCount = cardDao.graduatedCount(),
            matureCount = cardDao.matureCount(),
            totalReviews = cardDao.totalReviews(),
            totalLapses = cardDao.totalLapses(),
            forecast = forecast,
        )
    }
}
