package uk.co.barcodealarm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A night is named for its 20:00 start date. Boundaries follow local civil time/DST. */
data class BedtimeWindow(val night: LocalDate, val zone: ZoneId) {
    val start: Long = night.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
    val end: Long = night.plusDays(1).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()

    fun contains(millis: Long) = millis >= start && millis < end
}

object ChargingRules {
    fun window(now: Long, zone: ZoneId): BedtimeWindow {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        return BedtimeWindow(
            if (local.hour < 20) local.toLocalDate().minusDays(1) else local.toLocalDate(),
            zone,
        )
    }

    fun nextStart(now: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val today = BedtimeWindow(date, zone).start
        return if (today > now) today else BedtimeWindow(date.plusDays(1), zone).start
    }

    fun estimate(
        continuous: Boolean,
        powered: Boolean,
        connection: Long?,
        window: BedtimeWindow,
    ): Long? = connection?.takeIf { continuous && powered && window.contains(it) }
}
