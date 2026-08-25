package com.openchinese.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** SM-2 card lifecycle states. */
enum class CardState { NEW, LEARNING, GRADUATED }

/**
 * Per-user SRS progress for one word. Lives in its own database file
 * ([SrsStateDatabase]) so it can be serialized wholesale into the Drive backup
 * while the static dictionary stays out of sync payloads entirely.
 *
 * All timestamps are epoch milliseconds UTC.
 */
@Entity(tableName = "cards")
data class FlashcardStateEntity(
    /** Same id as [WordEntity.id]. */
    @PrimaryKey val wordId: Long,

    val state: CardState = CardState.NEW,

    /** SM-2 ease factor; default 2.5 per algorithm spec. */
    val easeFactor: Float = 2.5f,

    /** Current inter-repetition interval in days (fractional while learning). */
    val intervalDays: Float = 0f,

    /** Successful consecutive repetitions at the current ease factor. */
    val repetitions: Int = 0,

    /** When the card is next due; epoch ms. NEW cards are due immediately. */
    val dueAt: Long = 0L,

    /** Epoch ms of last review; drives Last-Write-Wins conflict resolution. */
    val updatedAt: Long = 0L,

    /** Cumulative answer counts, kept for stats and future FSRS tuning. */
    val totalReviews: Int = 0,
    val lapses: Int = 0,
) {
    val isDue: Boolean get() = state == CardState.NEW || dueAt <= System.currentTimeMillis()
}
