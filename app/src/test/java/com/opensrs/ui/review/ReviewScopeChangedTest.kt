package com.opensrs.ui.review

import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.RomanizationPref
import com.opensrs.data.local.UserSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which settings changes force a queue rebuild (scopeChanged) vs. apply
 * display-only: limits, HSK window, and dialect ordering rebuild; toggles like
 * romanization or traditional emphasis must not.
 */
class ReviewScopeChangedTest {

    private fun settings(
        dailyNewLimit: Int = 10,
        dailyReviewLimit: Int = 100,
        hskMaxLevel: Int = 3,
        hskMinLevel: Int = 0,
        dialectMode: DialectMode = DialectMode.DUAL,
        romanization: RomanizationPref = RomanizationPref.PINYIN,
    ) = UserSettings(
        dailyNewLimit = dailyNewLimit,
        dailyReviewLimit = dailyReviewLimit,
        hskMaxLevel = hskMaxLevel,
        hskMinLevel = hskMinLevel,
        dialectMode = dialectMode,
        romanization = romanization,
        autoPlayTts = true,
        showEnglishFirst = false,
        emphasizeTraditional = false,
    )

    @Test
    fun `identical settings are not a scope change`() {
        assertFalse(ReviewViewModel.scopeChanged(settings(), settings()))
    }

    @Test
    fun `daily limit changes are scope changes`() {
        assertTrue(ReviewViewModel.scopeChanged(settings(), settings(dailyNewLimit = 20)))
        assertTrue(ReviewViewModel.scopeChanged(settings(), settings(dailyReviewLimit = 200)))
    }

    @Test
    fun `hsk band window changes are scope changes`() {
        assertTrue(ReviewViewModel.scopeChanged(settings(), settings(hskMaxLevel = 5)))
        assertTrue(ReviewViewModel.scopeChanged(settings(), settings(hskMinLevel = 2)))
    }

    @Test
    fun `dialect change is a scope change via frequency ordering`() {
        assertTrue(
            ReviewViewModel.scopeChanged(
                settings(),
                settings(dialectMode = DialectMode.MANDARIN),
            ),
        )
    }

    @Test
    fun `display-only changes are not scope changes`() {
        val base = settings()
        assertFalse(ReviewViewModel.scopeChanged(base, settings(romanization = RomanizationPref.JYUTPING)))
        assertFalse(
            ReviewViewModel.scopeChanged(base, base.copy(emphasizeTraditional = true, autoPlayTts = false)),
        )
    }
}
