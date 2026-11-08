package io.github.thewisekd.phopencode.feature.sessions.ui.list

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

/**
 * A week back and older, a row shows its date in the screen's language: month and day for this year,
 * with the year for another. The month-day form is built without a year, so no language's year
 * separator can be left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class SessionDateTextTest {
    private val thisYear = NOW - 30.days
    private val lastYear = NOW - 400.days

    private fun date(locale: Locale, at: kotlin.time.Instant) = shortDate(at, NOW, locale, ZoneOffset.UTC)

    @Test
    fun `English shows month and day, and the year for another year`() {
        assertEquals("Feb 13", date(Locale.ENGLISH, thisYear))
        assertEquals("Feb 8, 2025", date(Locale.ENGLISH, lastYear))
    }

    @Test
    fun `Turkish puts the day first`() {
        val turkish = Locale.forLanguageTag("tr")
        assertEquals("13 Şub", date(turkish, thisYear))
        assertEquals("8 Şub 2025", date(turkish, lastYear))
    }

    @Test
    fun `a year-first language leaves no stray separator`() {
        val japanese = Locale.JAPANESE
        assertEquals("2月13日", date(japanese, thisYear))
        assertEquals("2025年2月8日", date(japanese, lastYear))
        val chinese = Locale.SIMPLIFIED_CHINESE
        assertEquals("2月13日", date(chinese, thisYear))
        assertEquals("2025年2月8日", date(chinese, lastYear))
    }
}
