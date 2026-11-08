package io.github.thewisekd.phopencode.feature.sessions.ui.list

import android.icu.text.DateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thewisekd.phopencode.feature.sessions.ui.R
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.coroutines.delay

/** Where the session list reads the time from; tests provide a fixed clock. */
internal val LocalSessionListClock = staticCompositionLocalOf<Clock> { Clock.System }

/** How often the relative times are read again while the list is started. */
private val TICK = 1.minutes

/**
 * The time the rows measure their age against. It is read again every [TICK] while the screen is at
 * least started, and at once when it starts again, so the times move on by themselves; a stopped
 * screen does not tick. Between ticks nothing is due for a minute, so the screen comes to rest, and a
 * clock that stands still, as in tests, only redraws when its time changes.
 */
@Composable
internal fun rememberListNow(): Instant {
    val clock = LocalSessionListClock.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember(clock) { mutableStateOf(clock.now()) }
    LaunchedEffect(clock, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock.now()
                delay(TICK)
            }
        }
    }
    return now
}

/** A week back and older, a session shows its date instead of an age. */
private val DATE_AFTER = 7.days

/** ICU skeletons: the fields a date shows, which each language orders and punctuates its own way. */
private const val MONTH_DAY = "MMMd"
private const val YEAR_MONTH_DAY = "yMMMd"

/**
 * How long ago [updatedAt] was, in the screen's language: "just now", "5 min ago", "3 h ago",
 * "4 days ago", and from a week back the date, with the year only when it is not this year's. A time
 * after [now], from a server clock that runs ahead, reads as just now.
 */
@Composable
internal fun relativeTime(updatedAt: Instant, now: Instant): String {
    val age = now - updatedAt
    return when {
        age < 1.minutes -> stringResource(R.string.sessions_list_time_now)
        age < 1.hours -> age.inWholeMinutes.toInt().let { pluralStringResource(R.plurals.sessions_list_time_minutes, it, it) }
        age < 1.days -> age.inWholeHours.toInt().let { pluralStringResource(R.plurals.sessions_list_time_hours, it, it) }
        age < DATE_AFTER -> age.inWholeDays.toInt().let { pluralStringResource(R.plurals.sessions_list_time_days, it, it) }
        else -> {
            val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
            shortDate(updatedAt, now, locale)
        }
    }
}

/**
 * "Feb 13" in English, "13 Şub" in Turkish, "2月13日" in Japanese; with the year, as in "Feb 8, 2025",
 * when it differs from [now]'s. Each form comes from the language's own pattern for a month-day or a
 * year-month-day skeleton, so this year's dates never had a year, nor its separators, to take out.
 */
internal fun shortDate(at: Instant, now: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
    val sameYear = at.toJavaInstant().atZone(zone).year == now.toJavaInstant().atZone(zone).year
    val format = IcuDateFormat.getInstanceForSkeleton(if (sameYear) MONTH_DAY else YEAR_MONTH_DAY, locale)
    format.timeZone = IcuTimeZone.getTimeZone(zone.id)
    return format.format(Date(at.toEpochMilliseconds()))
}
