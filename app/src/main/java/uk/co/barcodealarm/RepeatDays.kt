package uk.co.barcodealarm

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Display days in calendar order, regardless of the order in which they were checked. */
object RepeatDays {
    fun shortName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    fun describe(days: Set<DayOfWeek>): String {
        if (days.isEmpty()) return "One time"
        if (days.size == 7) return "Every day"
        return days.sortedBy { day -> day.value }.joinToString(", ") { day -> shortName(day) }
    }
}
