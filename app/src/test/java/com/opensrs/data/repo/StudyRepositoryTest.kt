package com.opensrs.data.repo

import com.opensrs.data.db.CardState
import com.opensrs.data.db.FlashcardDao
import com.opensrs.data.db.FlashcardStateEntity
import com.opensrs.data.db.WordDao
import com.opensrs.data.db.WordEntity
import com.opensrs.srs.SrsScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for [StudyRepository.buildSession] — i.e. *which cards are
 * shown* and in what order — across every branch of the queue assembly:
 *
 *  - due reviews (LEARNING/GRADUATED, dueAt <= now) first, ordered by dueAt,
 *    capped by [reviewLimit];
 *  - new cards (no/incomplete state) next, in frequency order, capped by
 *    [newLimit];
 *  - answered cards hidden until their interval actually elapses;
 *  - permanently-known (SUSPENDED) words excluded everywhere;
 *  - the learn-ahead fallback when nothing is immediately due.
 *
 * The critical regression (answered cards resurfacing as "new") is pinned by
 * [answeredNewCardIsExcludedFromNextSession].
 */
class StudyRepositoryTest {

    private val MIN = 60_000L
    private val DAY = 24 * 60 * MIN

    private fun word(id: Long) = WordEntity(
        id = id, simplified = "w$id", traditional = "w$id",
        pinyin = "p$id", jyutping = "j$id", english = "e$id",
        mandarinRank = id.toInt(), cantoneseRank = null,
        examplesJson = "[]", hskLevel = 1,
    )

    private val stubWords = (1L..10L).map { word(it) }

    private fun dueCard(
        id: Long,
        dueAt: Long,
        state: CardState = CardState.GRADUATED,
        intervalDays: Float = 1f,
        ease: Float = 2.5f,
        reps: Int = 1,
    ) = FlashcardStateEntity(
        wordId = id, state = state, easeFactor = ease, intervalDays = intervalDays,
        repetitions = reps, dueAt = dueAt, updatedAt = dueAt,
        totalReviews = 1, lapses = 0,
    )

    private class StubWordDao(private val rows: List<WordEntity>) : WordDao {
        var lastWindow = -1
        var lastPreferCantonese = false
        var lastMaxLevel = -1
        var lastMinLevel = -1

        override suspend fun topBySpokenFrequency(
            window: Int,
            preferCantonese: Boolean,
            maxLevel: Int,
            minLevel: Int,
        ): List<WordEntity> {
            lastWindow = window
            lastPreferCantonese = preferCantonese
            lastMaxLevel = maxLevel
            lastMinLevel = minLevel
            return rows.take(window)
        }

        override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun count() = rows.size
        override suspend fun countInLevels(maxLevel: Int) = rows.size
        override suspend fun allForSearch() = rows
        override suspend fun search(query: String) = emptyList<WordEntity>()
    }

    /** In-memory FlashcardDao backed by a map; mirrors the SQL predicates. */
    private class MemoryFlashcardDao : FlashcardDao {
        private val cards = LinkedHashMap<Long, FlashcardStateEntity>()

        override suspend fun byWord(wordId: Long) = cards[wordId]
        override suspend fun statesFor(wordIds: List<Long>) =
            cards.values.filter { it.wordId in wordIds && it.state != CardState.SUSPENDED }
        override suspend fun dueKnown(limit: Int, now: Long) =
            cards.values
                .filter { it.state in setOf(CardState.LEARNING, CardState.GRADUATED) && it.dueAt <= now }
                .sortedBy { it.dueAt }
                .take(limit)
        override suspend fun nextLearning(limit: Int, now: Long, horizon: Long) =
            cards.values
                .filter { it.state == CardState.LEARNING && it.dueAt > now && it.dueAt <= horizon }
                .sortedBy { it.dueAt }
                .take(limit)
        override fun newCount() = flowOf(cards.values.count { it.state == CardState.NEW })
        override suspend fun dueCountBetween(dayStart: Long, dayEnd: Long) = 0
        override suspend fun graduatedCount() = cards.values.count { it.state == CardState.GRADUATED }
        override suspend fun totalReviews() = cards.values.sumOf { it.totalReviews }
        override suspend fun totalLapses() = cards.values.sumOf { it.lapses }
        override suspend fun matureCount() = 0
        override fun dueCount(now: Long) = flowOf(
            cards.values.count { it.state == CardState.NEW || it.dueAt <= now },
        )
        override suspend fun all() = cards.values.toList()
        override suspend fun skippedIds() =
            cards.values.filter { it.state == CardState.SUSPENDED }.map { it.wordId }
        override suspend fun maxUpdatedAt() = cards.values.maxOfOrNull { it.updatedAt }
        override suspend fun upsert(card: FlashcardStateEntity) {
            cards[card.wordId] = card
        }
        /** Non-suspend insert for test setup; [upsert] is suspend. */
        fun seed(card: FlashcardStateEntity) {
            cards[card.wordId] = card
        }
        override suspend fun upsertAll(c: List<FlashcardStateEntity>) = c.forEach { upsert(it) }
        override suspend fun deleteNotIn(keepWordIds: List<Long>) {
            cards.keys.retainAll(keepWordIds.toSet())
        }
        override suspend fun clear() = cards.clear()
    }

    /** Triple of (repository, word dao, card dao) so tests can inspect/reseed state. */
    private fun built(
        words: List<WordEntity> = stubWords,
        seed: List<FlashcardStateEntity> = emptyList(),
    ): Triple<StudyRepository, StubWordDao, MemoryFlashcardDao> {
        val cardDao = MemoryFlashcardDao()
        seed.forEach { cardDao.seed(it) }
        val wordDao = StubWordDao(words)
        return Triple(StudyRepository(wordDao, cardDao, SrsScheduler()), wordDao, cardDao)
    }

    // -- New cards -------------------------------------------------------------

    @Test
    fun `new cards respect newLimit in frequency order`() = runBlocking {
        val (r) = built()
        val session = r.buildSession(newLimit = 3, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L, 2L, 3L), session.map { it.wordId })
    }

    @Test
    fun `newLimit of zero yields no new cards`() = runBlocking {
        val (r) = built()
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertTrue("no new cards when newLimit=0", session.isEmpty())
    }

    @Test
    fun `new card with an existing NEW row is still treated as new`() = runBlocking {
        val (r) = built(seed = listOf(dueCard(1, 0, CardState.NEW)))
        val session = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L, 2L), session.map { it.wordId })
    }

    // -- Due reviews -----------------------------------------------------------

    @Test
    fun `due reviews respect reviewLimit and are ordered by dueAt ascending`() = runBlocking {
        val now = System.currentTimeMillis()
        // id=1 most overdue (now-5000), id=5 least (now-1000)
        val seed = (1L..5L).map { dueCard(it, now - (6 - it) * 1000) }
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 2, preferCantonese = false)
        assertEquals(listOf(1L, 2L), session.map { it.wordId })
    }

    @Test
    fun `due reviews are shown before new cards`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = listOf(dueCard(1, now - 1000))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        // id=1 is due; ids 2,3 are the new fill. Due must lead.
        assertEquals(listOf(1L, 2L, 3L), session.map { it.wordId })
        assertEquals(CardState.GRADUATED, session[0].state.state)
    }

    @Test
    fun `mix of due reviews and new cards respects both caps`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = listOf(dueCard(1, now - 3000), dueCard(2, now - 2000), dueCard(3, now - 1000))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 2, reviewLimit = 2, preferCantonese = false)
        // 2 due (asc by dueAt: id=1 most overdue, then id=2) then 2 new (4,5).
        assertEquals(listOf(1L, 2L, 4L, 5L), session.map { it.wordId })
        assertEquals(4, session.size)
    }

    @Test
    fun `reviewLimit of zero hides due reviews when new cards exist`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = listOf(dueCard(1, now - 1000))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 2, reviewLimit = 0, preferCantonese = false)
        // No due pulled (limit 0); new fill takes over from id=2 (id=1 not new).
        assertEquals(listOf(2L, 3L), session.map { it.wordId })
    }

    // -- Hidden / answered cards ----------------------------------------------

    @Test
    fun `answered new card is excluded from the next session`() = runBlocking {
        val (r) = built()
        val first = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L, 2L), first.map { it.wordId })

        // Answer the first card "Easy" (rating 3): graduates with a future due date.
        r.answer(first[0].state, rating = 3)

        val second = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        assertFalse("answered card must not reappear as new", 1L in second.map { it.wordId })
        assertEquals(listOf(2L, 3L), second.map { it.wordId })
    }

    @Test
    fun `not-yet-due graduated card is hidden when no new slots`() = runBlocking {
        val now = System.currentTimeMillis()
        // id=1 graduated but due 4 days out; no new cards because newLimit=0.
        val seed = listOf(dueCard(1, now + 4 * DAY))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertTrue("future-due card must not show", session.none { it.wordId == 1L })
    }

    @Test
    fun `answered card reappears as a due review once its interval elapses`() = runBlocking {
        val (r, _, cardDao) = built()
        val first = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        r.answer(first[0].state, rating = 3) // id=1 graduated, due +4d (persisted in cardDao)

        // Simulate the interval elapsing by flipping the stored card overdue.
        cardDao.upsert(dueCard(1, System.currentTimeMillis() - 1000))
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), session.map { it.wordId })
    }

    @Test
    fun `card answered Good stays out of the normal queue`() = runBlocking {
        val (r, _, cardDao) = built()
        val first = r.buildSession(newLimit = 3, reviewLimit = 100, preferCantonese = false) // [1,2,3]
        r.answer(first[0].state, rating = 2) // Good -> LEARNING, due ~now+10m (persisted)

        // With other new cards present, learn-ahead is suppressed: id=1 must not
        // appear as due (future dueAt) nor as new (it is LEARNING, not NEW).
        val session = r.buildSession(newLimit = 3, reviewLimit = 100, preferCantonese = false)
        assertTrue("learned card must not reappear as due or new", session.none { it.wordId == 1L })
        assertEquals(listOf(2L, 3L, 4L), session.map { it.wordId })
    }

    // -- Permanently known (SUSPENDED) ----------------------------------------

    @Test
    fun `markKnown excludes the word from future sessions`() = runBlocking {
        val (r) = built()
        val session = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        r.markKnown(session[0].state)
        val again = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        assertTrue(again.none { it.wordId == session[0].wordId })
    }

    @Test
    fun `suspended word is filtered from candidates entirely`() = runBlocking {
        val seed = listOf(dueCard(1, System.currentTimeMillis() - 1000, CardState.SUSPENDED))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 2, reviewLimit = 100, preferCantonese = false)
        // id=1 is suspended -> never a candidate, never due; new fill starts at id=2.
        assertEquals(listOf(2L, 3L), session.map { it.wordId })
    }

    // -- Learn-ahead fallback --------------------------------------------------

    @Test
    fun `learn-ahead surfaces a soon-due learning card when nothing is due`() = runBlocking {
        val now = System.currentTimeMillis()
        // id=1 learning, due in 10m (inside the 20m learn-ahead window); no new cards.
        val seed = listOf(
            FlashcardStateEntity(wordId = 1, state = CardState.LEARNING, dueAt = now + 10 * MIN),
        )
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), session.map { it.wordId })
    }

    @Test
    fun `learn-ahead excludes learning cards beyond the horizon`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = listOf(
            FlashcardStateEntity(wordId = 1, state = CardState.LEARNING, dueAt = now + 30 * MIN),
        )
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertTrue("card due beyond learn-ahead horizon must not show", session.isEmpty())
    }

    @Test
    fun `answering Good routes the card into learn-ahead`() = runBlocking {
        val (r, _, cardDao) = built()
        val first = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        r.answer(first[0].state, rating = 2) // Good -> LEARNING, due ~now+10m (persisted)

        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), session.map { it.wordId })
    }

    @Test
    fun `answering Again routes the lapsed card into learn-ahead`() = runBlocking {
        val (r, _, cardDao) = built()
        val first = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        r.answer(first[0].state, rating = 0) // Again -> LEARNING step 1, due ~now+1m (persisted)

        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), session.map { it.wordId })
    }

    // -- Parameter forwarding & edge cases ------------------------------------

    @Test
    fun `candidate window is derived from newLimit and forwarded to word dao`() = runBlocking {
        val (r, wordDao) = built()
        r.buildSession(newLimit = 7, reviewLimit = 100, preferCantonese = true, hskMaxLevel = 3, hskMinLevel = 1)
        // window = max(newLimit,1)*5 + 100 = 7*5+100 = 135
        assertEquals(135, wordDao.lastWindow)
        assertTrue(wordDao.lastPreferCantonese)
        assertEquals(3, wordDao.lastMaxLevel)
        assertEquals(1, wordDao.lastMinLevel)
    }

    @Test
    fun `empty dictionary yields an empty session without error`() = runBlocking {
        val (r) = built(words = emptyList())
        val session = r.buildSession(newLimit = 5, reviewLimit = 100, preferCantonese = false)
        assertTrue(session.isEmpty())
    }

    @Test
    fun `no duplicates when a word is both due and a candidate`() = runBlocking {
        val now = System.currentTimeMillis()
        // id=1 is due (graduated). It also appears in candidates, but must not be
        // duplicated into the new-card slot.
        val seed = listOf(dueCard(1, now - 1000))
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 3, reviewLimit = 100, preferCantonese = false)
        assertEquals(1, session.count { it.wordId == 1L })
        assertEquals(listOf(1L, 2L, 3L, 4L), session.map { it.wordId })
    }

    @Test
    fun `answer returns the updated scheduling state`() = runBlocking {
        val (r) = built()
        val first = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        val next = r.answer(first[0].state, rating = 3)
        assertSame(CardState.GRADUATED, next.state)
        assertTrue("easy graduation must push the due date into the future", next.dueAt > System.currentTimeMillis())
    }

    // -- Additional behavioral scenarios --------------------------------------

    @Test
    fun `newLimit larger than available candidates shows only what exists`() = runBlocking {
        // The dictionary has exactly 10 stub words but we ask for 20 new cards.
        val (r) = built()
        val session = r.buildSession(newLimit = 20, reviewLimit = 0, preferCantonese = false)
        // Fewer than the limit: only the 10 available new cards are shown.
        assertEquals(10, session.size)
        assertEquals((1L..10L).toList(), session.map { it.wordId })
    }

    @Test
    fun `reviewLimit larger than due cards shows all of them in order`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = (1L..3L).map { dueCard(it, now - (4 - it) * 1000) } // id=1 most overdue
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(3, session.size)
        assertEquals(listOf(1L, 2L, 3L), session.map { it.wordId })
    }
    @Test
    fun `overdue LEARNING card appears via dueKnown ahead of new cards`() = runBlocking {
        val now = System.currentTimeMillis()
        // id=1 is LEARNING and already overdue (dueAt < now); id=2 is GRADUATED and due.
        val seed = listOf(
            FlashcardStateEntity(wordId = 1, state = CardState.LEARNING, dueAt = now - 2000),
            dueCard(2, now - 1000),
        )
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        // Both due, ordered by dueAt: id=1 (more overdue) then id=2, then new fill id=3.
        assertEquals(listOf(1L, 2L, 3L), session.map { it.wordId })
        assertEquals(CardState.LEARNING, session[0].state.state)
        assertEquals(CardState.GRADUATED, session[1].state.state)
    }

    @Test
    fun `graduated card answered Hard stays out of the normal queue`() = runBlocking {
        val now = System.currentTimeMillis()
        val (r, _, cardDao) = built(seed = listOf(dueCard(1, now)))
        val first = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), first.map { it.wordId })
        // Hard on a graduated card -> still GRADUATED with a future due date.
        val next = r.answer(first[0].state, rating = 1)
        assertSame(CardState.GRADUATED, next.state)
        assertTrue("hard review must push the due date into the future", next.dueAt > System.currentTimeMillis())

        // No new slots and the card is now future-due -> hidden (learn-ahead ignores GRADUATED).
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertTrue("graduated card must not resurface until its interval elapses", session.none { it.wordId == 1L })
    }

    @Test
    fun `graduated card lapsed with Again becomes LEARNING and surfaces via learn-ahead`() = runBlocking {
        val now = System.currentTimeMillis()
        val (r, _, cardDao) = built(seed = listOf(dueCard(1, now)))
        val first = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), first.map { it.wordId })
        // Again on a graduated card -> lapse: LEARNING, due ~now+1m.
        val next = r.answer(first[0].state, rating = 0)
        assertSame(CardState.LEARNING, next.state)
        assertTrue(
            "lapse must re-enter learning due within a few minutes",
            next.dueAt > System.currentTimeMillis() && next.dueAt <= System.currentTimeMillis() + 2 * MIN,
        )

        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), session.map { it.wordId })
        assertEquals(CardState.LEARNING, session[0].state.state)
    }

    @Test
    fun `learn-ahead returns soon-due learning cards in dueAt ascending order`() = runBlocking {
        val now = System.currentTimeMillis()
        // Seed order is scrambled; learn-ahead must re-sort by dueAt ascending.
        val seed = listOf(
            FlashcardStateEntity(wordId = 1, state = CardState.LEARNING, dueAt = now + 15 * MIN),
            FlashcardStateEntity(wordId = 2, state = CardState.LEARNING, dueAt = now + 3 * MIN),
            FlashcardStateEntity(wordId = 3, state = CardState.LEARNING, dueAt = now + 8 * MIN),
            FlashcardStateEntity(wordId = 4, state = CardState.LEARNING, dueAt = now + 1 * MIN),
        )
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(4L, 2L, 3L, 1L), session.map { it.wordId })
    }

    @Test
    fun `learn-ahead caps at LEARN_AHEAD_LIMIT even with more learning cards due`() = runBlocking {
        val now = System.currentTimeMillis()
        // 15 learning cards, all due within the 20m horizon.
        val seed = (1L..15L).map { id ->
            FlashcardStateEntity(wordId = id, state = CardState.LEARNING, dueAt = now + id * MIN)
        }
        val (r) = built(seed = seed)
        val session = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(10, session.size)
        // Earliest 10 by dueAt (ids 1..10) are returned, in order.
        assertEquals((1L..10L).toList(), session.map { it.wordId })
    }
    @Test
    fun `card progresses NEW to LEARNING to GRADUATED across simulated sessions`() = runBlocking {
        val (r, _, cardDao) = built()
        // Session 1: new card id=1 shown as NEW.
        val s1 = r.buildSession(newLimit = 1, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), s1.map { it.wordId })
        assertEquals(CardState.NEW, s1[0].state.state)

        // Answer Good -> enters LEARNING (step 1, due ~now+10m).
        val afterFirstGood = r.answer(s1[0].state, rating = 2)
        assertEquals(CardState.LEARNING, afterFirstGood.state)

        // Session 2: no new slots -> learn-ahead surfaces the LEARNING card.
        val s2 = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), s2.map { it.wordId })
        assertEquals(CardState.LEARNING, s2[0].state.state)

        // Answer Good at step 1 -> still LEARNING, advanced to the final step.
        val afterSecondGood = r.answer(s2[0].state, rating = 2)
        assertEquals(CardState.LEARNING, afterSecondGood.state)

        // Session 3: learn-ahead surfaces the card once more at its final step.
        val s3 = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), s3.map { it.wordId })
        assertEquals(CardState.LEARNING, s3[0].state.state)

        // Answer Good at the final step -> GRADUATED (due ~now+1d).
        val afterGraduate = r.answer(s3[0].state, rating = 2)
        assertEquals(CardState.GRADUATED, afterGraduate.state)
        assertTrue("graduation must schedule a future review", afterGraduate.dueAt > System.currentTimeMillis())

        // Session 4: GRADUATED card is future-due with no new slots -> queue empty.
        val s4 = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertTrue("graduated card must stay hidden until interval elapses", s4.isEmpty())

        // Simulate the interval elapsing: flip the stored card overdue.
        cardDao.upsert(dueCard(1, System.currentTimeMillis() - 1000))
        val s5 = r.buildSession(newLimit = 0, reviewLimit = 100, preferCantonese = false)
        assertEquals(listOf(1L), s5.map { it.wordId })
    }

    @Test
    fun `preferCantonese false and default hsk levels are forwarded to word dao`() = runBlocking {
        val (r, wordDao) = built()
        r.buildSession(newLimit = 4, reviewLimit = 50, preferCantonese = false, hskMaxLevel = 0, hskMinLevel = 0)
        // window = max(4,1)*5 + 100 = 120
        assertEquals(120, wordDao.lastWindow)
        assertFalse(wordDao.lastPreferCantonese)
        assertEquals(0, wordDao.lastMaxLevel)
        assertEquals(0, wordDao.lastMinLevel)
    }
}
