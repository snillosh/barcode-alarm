package uk.co.barcodealarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime

/** Pure business rules: no Android APIs, storage, or screen state. `object` is a singleton. */
object AlarmRules {
    const val MATHS_DURATION_MILLIS = 300_000L
    const val REQUIRED_CORRECT_ANSWERS = 15

    const val SNOOZE_MINUTES = 5
    const val SNOOZE_DURATION_MILLIS = SNOOZE_MINUTES * 60_000L

    fun calculateNextOccurrence(
        hour: Int,
        minute: Int,
        now: ZonedDateTime,
        repeatDays: Set<DayOfWeek> = emptySet(),
    ): Long {
        // Scan calendar dates rather than adding 24 hours: DST days can be 23 or 25 hours.
        for (daysAhead in 0L..7L) {
            val date = now.toLocalDate().plusDays(daysAhead)
            if (repeatDays.isNotEmpty() && date.dayOfWeek !in repeatDays) continue
            val occurrence = date.atTime(hour, minute).atZone(now.zone)
            if (occurrence.isAfter(now)) return occurrence.toInstant().toEpochMilli()
        }
        error("No next alarm occurrence found")
    }

    fun prepareEnabled(alarm: Alarm, scheduledAtMillis: Long): Alarm {
        check(!alarm.isRinging) { "Alarm is ringing" }
        return alarm.copy(
            isEnabled = true,
            scheduledAtMillis = scheduledAtMillis,
            isSnoozed = false,
            playbackStartedAtElapsedMillis = -1,
            occurrenceScheduledAtMillis = 0,
            mathsStartedAtElapsedMillis = -1,
            correctAnswerCount = 0,
        )
    }

    fun markTriggered(alarm: Alarm): Alarm {
        // A series remains enabled while ringing, but has no second pending occurrence yet.
        // The receiver's isRinging check still rejects duplicate broadcasts.
        return alarm.copy(
            isEnabled = alarm.repeatDays.isNotEmpty(),
            scheduledAtMillis = 0,
            isRinging = true,
            isSnoozed = false,
            playbackStartedAtElapsedMillis = -1,
            occurrenceScheduledAtMillis =
                if (alarm.isSnoozed) {
                    alarm.occurrenceScheduledAtMillis
                } else alarm.scheduledAtMillis,
        )
    }

    fun snooze(alarm: Alarm, nowMillis: Long): Alarm {
        check(alarm.isRinging) { "Only a ringing alarm can be snoozed" }
        // Snooze delays this occurrence, even onto an unselected day. Its original time,
        // repeat days and already-earned maths progress are unchanged.
        return alarm.copy(
            isEnabled = true,
            isRinging = false,
            isSnoozed = true,
            playbackStartedAtElapsedMillis = -1,
            scheduledAtMillis = nowMillis + SNOOZE_DURATION_MILLIS,
        )
    }

    fun completeOccurrence(alarm: Alarm, now: ZonedDateTime): Alarm {
        check(alarm.isRinging) { "Alarm is not ringing" }
        val completed = markDismissed(alarm)
        if (!alarm.isEnabled || alarm.repeatDays.isEmpty()) return completed

        // A clock rollback must not register the same regular occurrence a second time.
        val referenceTime =
            if (alarm.occurrenceScheduledAtMillis > now.toInstant().toEpochMilli()) {
                Instant.ofEpochMilli(alarm.occurrenceScheduledAtMillis).atZone(now.zone)
            } else now
        val nextTime =
            calculateNextOccurrence(alarm.hour, alarm.minute, referenceTime, alarm.repeatDays)
        return prepareEnabled(completed, nextTime)
    }

    fun isNewDeviceBoot(lastRestoredBootCount: Int, currentBootCount: Int): Boolean {
        // Some Android versions re-send BOOT_COMPLETED when an app/process is restored.
        // A duplicate broadcast in the same device boot must not reset live timers.
        return currentBootCount < 0 || lastRestoredBootCount != currentBootCount
    }

    fun restoreAfterSystemChange(
        alarm: Alarm,
        now: ZonedDateTime,
        isClockChange: Boolean,
        isDeviceBoot: Boolean,
    ): Alarm {
        val restored =
            if ((alarm.isRinging || alarm.isSnoozed) && isDeviceBoot) {
                alarm.copy(
                    mathsStartedAtElapsedMillis = -1,
                    correctAnswerCount = 0,
                    playbackStartedAtElapsedMillis = -1,
                )
            } else alarm
        if (
            isDeviceBoot &&
                restored.isRinging &&
                restored.isEnabled &&
                restored.repeatDays.isNotEmpty()
        ) {
            // Android stopped the old playback at reboot. Do not let that interrupted
            // occurrence strand the series: restore a future selected day, without
            // starting a boot-time media service. One-shot alarms keep their old behaviour.
            return completeOccurrence(restored, now)
        }
        if (!restored.isEnabled || restored.isRinging) return restored

        val isFuture = restored.scheduledAtMillis > now.toInstant().toEpochMilli()
        val recalculateLocalRepeat =
            isClockChange && restored.repeatDays.isNotEmpty() && !restored.isSnoozed
        if (isFuture && !recalculateLocalRepeat) return restored
        if (restored.repeatDays.isEmpty()) return markDismissed(restored)

        // Missed repeats (including missed snoozes) advance to a future selected day.
        // Boot never starts a playback service or replays a missed occurrence immediately.
        val nextTime =
            calculateNextOccurrence(restored.hour, restored.minute, now, restored.repeatDays)
        return prepareEnabled(restored, nextTime)
    }

    fun barcodesMatch(
        value: String,
        format: String,
        otherValue: String,
        otherFormat: String,
    ): Boolean {
        val firstCode = normalizeBarcode(value, format)
        val secondCode = normalizeBarcode(otherValue, otherFormat)
        return firstCode == secondCode
    }

    private fun normalizeBarcode(value: String, format: String): Pair<String, String> {
        // Retail scanners may represent UPC-A as EAN-13 with a leading zero.
        if (format == "EAN_13" && value.length == 13 && value.startsWith("0")) {
            return Pair("UPC_A", value.drop(1))
        }
        return Pair(format, value)
    }

    fun shouldTrigger(alarm: Alarm, deliveredScheduledAtMillis: Long): Boolean {
        // A receiver delivery must still refer to the currently saved one-shot occurrence.
        return alarm.isEnabled &&
            !alarm.isRinging &&
            alarm.scheduledAtMillis == deliveredScheduledAtMillis
    }

    fun canDismissWithMaths(alarm: Alarm, elapsedRealtimeMillis: Long): Boolean {
        val startTime = alarm.mathsStartedAtElapsedMillis
        val challengeStarted = startTime >= 0
        val clockHasNotReset = elapsedRealtimeMillis >= startTime
        val enoughTimePassed = elapsedRealtimeMillis - startTime >= MATHS_DURATION_MILLIS
        val enoughAnswers = alarm.correctAnswerCount >= REQUIRED_CORRECT_ANSWERS
        return challengeStarted && clockHasNotReset && enoughTimePassed && enoughAnswers
    }

    fun markDismissed(alarm: Alarm): Alarm {
        // Clear one occurrence. The scheduler decides whether an enabled series should continue.
        return alarm.copy(
            isEnabled = false,
            isSnoozed = false,
            playbackStartedAtElapsedMillis = -1,
            occurrenceScheduledAtMillis = 0,
            scheduledAtMillis = 0,
            isRinging = false,
            mathsStartedAtElapsedMillis = -1,
            correctAnswerCount = 0,
        )
    }
}
