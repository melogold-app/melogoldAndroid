package app.melogold.android.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The periods of "Insights" (tasks/0016): a week, a month, a year, everything. */
enum class StatsPeriod { Week, Month, Year, AllTime }

/**
 * One period of [StatsPeriod] and where it is: [offset] 0 is the current one, -1 the one before it, and so on. It runs
 * from [start] (inclusive) to [end] (exclusive), epoch milliseconds, drawn from the local dates [firstDay] and
 * [endDay] (exclusive) in the time zone of the device. A week starts on Monday, whatever the locale says. All time has
 * no dates and no offset.
 */
data class StatsWindow(
    val period: StatsPeriod,
    val offset: Int,
    val firstDay: LocalDate?,
    val endDay: LocalDate?,
    val start: Long,
    val end: Long
) {
    val isCurrent get() = offset == 0

    /** The days of the period: 7 for a week, 28 to 31 for a month, 365 or 366 for a year; 0 for all time. */
    val days: Int get() = if (firstDay == null || endDay == null) 0 else (endDay.toEpochDay() - firstDay.toEpochDay()).toInt()

    fun contains(timestamp: Long) = timestamp in start until end

    /** The same period one step back, for "vs last month"; none for all time. */
    fun previous(zone: ZoneId): StatsWindow? {
        val first = firstDay ?: return null
        return statsWindow(period, offset = -1, today = first, zone = zone).copy(offset = offset - 1)
    }
}

/**
 * The period [period] [offset] steps from the one [today] is in, in [zone]. The boundaries are local midnights, so a
 * day, a month or a year is what the person's calendar says even across a change of the clocks.
 */
fun statsWindow(
    period: StatsPeriod,
    offset: Int = 0,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): StatsWindow {
    if (period == StatsPeriod.AllTime) return StatsWindow(period, 0, null, null, start = 0L, end = Long.MAX_VALUE)

    val first = when (period) {
        StatsPeriod.Week -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
        StatsPeriod.Month -> today.withDayOfMonth(1).plusMonths(offset.toLong())
        StatsPeriod.Year -> today.withDayOfYear(1).plusYears(offset.toLong())
        StatsPeriod.AllTime -> error("handled above")
    }
    val end = when (period) {
        StatsPeriod.Week -> first.plusWeeks(1)
        StatsPeriod.Month -> first.plusMonths(1)
        else -> first.plusYears(1)
    }

    return StatsWindow(
        period = period,
        offset = offset,
        firstDay = first,
        endDay = end,
        start = first.atStartOfDay(zone).toInstant().toEpochMilli(),
        end = end.atStartOfDay(zone).toInstant().toEpochMilli()
    )
}

/**
 * The year "Insights <year> are ready" is about, from 1 December to 31 January: the year that ends (or has just ended);
 * null the rest of the year.
 */
fun wrappedSeasonYear(today: LocalDate): Int? = when (today.month) {
    java.time.Month.DECEMBER -> today.year
    java.time.Month.JANUARY -> today.year - 1
    else -> null
}
