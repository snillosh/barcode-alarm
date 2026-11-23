package uk.co.barcodealarm

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small editor/list operations. Scheduling remains the responsibility of AlarmScheduler. */
object AlarmOverview {
    const val MAX_LABEL_LENGTH = 50

    fun cleanLabel(label: String): String = label.trim().take(MAX_LABEL_LENGTH)

    fun displayLabel(alarm: Alarm): String = cleanLabel(alarm.label).ifEmpty { "Alarm" }

    fun duplicate(alarm: Alarm, newId: Int): Alarm {
        require(newId > 0 && newId != alarm.id) { "A duplicate needs its own alarm ID" }
        // markDismissed clears every execution field, including snooze, maths and playback.
        // Resources are references; copying an alarm never copies or deletes the resources.
        return AlarmRules.markDismissed(alarm).copy(id = newId, label = cleanLabel(alarm.label))
    }

    fun ongoingAlarms(occurrences: List<Occurrence>): List<Occurrence> =
        occurrences.filter { it.source == OccurrenceSource.ALARM && !it.terminal }

    fun nextScheduled(
        alarms: List<Alarm>,
        nowMillis: Long,
        occurrences: List<Occurrence> = emptyList(),
    ): Alarm? {
        // A snooze belongs to its occurrence, not the saved repeating/one-shot definition.
        // These temporary view records are never saved or used to schedule Android events.
        val snoozes =
            ongoingAlarms(occurrences)
                .filter { it.stage == OccurrenceStage.SNOOZED }
                .map {
                    it.snapshot.copy(
                        isEnabled = true,
                        isRinging = false,
                        isSnoozed = true,
                        scheduledAtMillis = it.snoozeAt,
                    )
                }
        return (alarms + snoozes)
            .filter { it.isEnabled && !it.isRinging && it.scheduledAtMillis > nowMillis }
            .minWithOrNull(compareBy<Alarm> { it.scheduledAtMillis }.thenBy { it.id })
    }

    fun occurrenceStatus(occurrence: Occurrence, zone: ZoneId = ZoneId.systemDefault()): String =
        when (occurrence.stage) {
            OccurrenceStage.SNOOZED ->
                "Snoozed until ${Instant.ofEpochMilli(occurrence.snoozeAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm"))}"
            OccurrenceStage.SECOND_WAIT -> "Silenced · Waiting for second barcode"
            OccurrenceStage.SECOND_READY -> "Second barcode ready"
            OccurrenceStage.VERIFY_DELAY -> "Wake-up verification scheduled"
            OccurrenceStage.VERIFY_RESPONSE -> "Are you still awake?"
            else -> "Alarm ringing"
        }

    fun countdown(
        alarms: List<Alarm>,
        nowMillis: Long,
        occurrences: List<Occurrence> = emptyList(),
    ): String {
        val next = nextScheduled(alarms, nowMillis, occurrences)
        if (next != null) return "${formatDuration(next.scheduledAtMillis - nowMillis)} remaining"
        val ongoing = ongoingAlarms(occurrences)
        return when {
            ongoing.any { it.audible } -> "Alarm ringing"
            ongoing.any { it.stage == OccurrenceStage.SNOOZED } -> "Snoozed alarm due now"
            ongoing.isNotEmpty() -> occurrenceStatus(ongoing.first())
            else -> "No upcoming alarms"
        }
    }

    fun enableConfirmation(alarm: Alarm, nowMillis: Long): String? {
        val scheduled = nextScheduled(listOf(alarm), nowMillis) ?: return null
        return "Alarm set for ${formatDuration(scheduled.scheduledAtMillis - nowMillis, natural = true)} from now"
    }

    /** Both messages use the same precision: days/hours, hours/minutes, or whole minutes. */
    fun formatDuration(millis: Long, natural: Boolean = false): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        if (minutes == 0L) return if (natural) "less than a minute" else "<1m"
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val parts =
            if (days > 0) listOf(days to "day", hours to "hour")
            else listOf(hours to "hour", minutes % 60 to "minute")
        return parts
            .filter { it.first > 0 }
            .joinToString(if (natural) " and " else " ") { (count, unit) ->
                if (natural) "$count $unit${if (count == 1L) "" else "s"}"
                else "$count${unit.first()}"
            }
    }

    fun scheduledDescription(
        alarm: Alarm,
        now: ZonedDateTime,
        use24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val scheduled = Instant.ofEpochMilli(alarm.scheduledAtMillis).atZone(now.zone)
        val day =
            when (scheduled.toLocalDate()) {
                now.toLocalDate() -> "Today"
                now.toLocalDate().plusDays(1) -> "Tomorrow"
                else -> scheduled.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
            }
        val time =
            scheduled.format(
                DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
            )
        return "${if (alarm.isSnoozed) "Snoozed · " else ""}$day at $time · ${displayLabel(alarm)}"
    }
}
