package uk.co.barcodealarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatAndSnoozeTest {
    private val thursdayMorning = ZonedDateTime.parse("2026-11-12T06:00:00Z[Europe/London]")
    private val alarm = Alarm(1, 7, 0, "shampoo", "QR_CODE", barcodeId = 2)
    private val weekdays =
        setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )

    private fun next(now: String, days: Set<DayOfWeek>, hour: Int = 7, minute: Int = 0): String {
        val time = AlarmRules.calculateNextOccurrence(hour, minute, ZonedDateTime.parse(now), days)
        return Instant.ofEpochMilli(time).toString()
    }

    @Test
    fun selectedDayLaterToday() {
        assertEquals(
            "2026-11-12T07:00:00Z",
            next(thursdayMorning.toString(), setOf(DayOfWeek.THURSDAY)),
        )
    }

    @Test
    fun passedTimeOnOnlySelectedDayWaitsAWeek() {
        assertEquals(
            "2026-11-19T07:00:00Z",
            next("2026-11-12T08:00:00Z[Europe/London]", setOf(DayOfWeek.THURSDAY)),
        )
    }

    @Test
    fun exactTimeWaitsForNextSelectedDay() {
        assertEquals(
            "2026-11-19T07:00:00Z",
            next("2026-11-12T07:00:00Z[Europe/London]", setOf(DayOfWeek.THURSDAY)),
        )
    }

    @Test
    fun weekdaysSkipWeekend() {
        assertEquals("2026-11-16T07:00:00Z", next("2026-11-13T08:00:00Z[Europe/London]", weekdays))
    }

    @Test
    fun weekendDaysAreSupported() {
        assertEquals(
            "2026-11-14T07:00:00Z",
            next("2026-11-13T08:00:00Z[Europe/London]", setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
        )
    }

    @Test
    fun sundayWrapsToMonday() {
        assertEquals(
            "2026-11-16T07:00:00Z",
            next("2026-11-15T08:00:00Z[Europe/London]", setOf(DayOfWeek.MONDAY)),
        )
    }

    @Test
    fun allDaysBehaveAsDailyAlarms() {
        assertEquals(
            "2026-11-13T07:00:00Z",
            next("2026-11-12T08:00:00Z[Europe/London]", DayOfWeek.values().toSet()),
        )
    }

    @Test
    fun emptySelectionStillMeansOneShotTomorrow() {
        assertEquals(
            "2026-11-13T07:00:00Z",
            next("2026-11-12T08:00:00Z[Europe/London]", emptySet()),
        )
    }

    @Test
    fun repeatAcrossSpringDstUsesLocalTime() {
        assertEquals(
            "2026-03-30T06:00:00Z",
            next("2026-03-28T08:00:00Z[Europe/London]", setOf(DayOfWeek.MONDAY)),
        )
    }

    @Test
    fun repeatInDstGapMovesForwardSensibly() {
        assertEquals(
            "2026-03-29T01:30:00Z",
            next("2026-03-28T08:00:00Z[Europe/London]", setOf(DayOfWeek.SUNDAY), 1, 30),
        )
    }

    @Test
    fun autumnOverlapDoesNotRingAgainAtSecondOffset() {
        assertEquals(
            "2026-11-01T01:30:00Z",
            next("2026-10-25T01:15:00Z[Europe/London]", setOf(DayOfWeek.SUNDAY), 1, 30),
        )
    }

    @Test
    fun triggeringRepeatConsumesDeliveryButKeepsSeriesEnabled() {
        val pending = AlarmRules.prepareEnabled(alarm.copy(repeatDays = weekdays), 12345)
        val ringing = AlarmRules.markTriggered(pending)
        assertTrue(ringing.isEnabled)
        assertTrue(ringing.isRinging)
        assertEquals(0L, ringing.scheduledAtMillis)
        assertEquals(12345L, ringing.occurrenceScheduledAtMillis)
        assertFalse(AlarmRules.shouldTrigger(ringing, 12345))
    }

    @Test
    fun triggeringOneShotStillDisablesIt() {
        val ringing = AlarmRules.markTriggered(AlarmRules.prepareEnabled(alarm, 12345))
        assertFalse(ringing.isEnabled)
        assertTrue(ringing.isRinging)
    }

    @Test
    fun snoozePreservesDefinitionAndMathsProgress() {
        val ringing =
            alarm.copy(
                isRinging = true,
                repeatDays = weekdays,
                mathsStartedAtElapsedMillis = 100,
                correctAnswerCount = 8,
                occurrenceScheduledAtMillis = 12345,
            )
        val snoozed = AlarmRules.snooze(ringing, 20000)
        assertEquals(
            ringing.copy(
                isEnabled = true,
                isRinging = false,
                isSnoozed = true,
                scheduledAtMillis = 320000,
            ),
            snoozed,
        )
    }

    @Test
    fun snoozeRejectsAnAlarmThatIsNotRinging() {
        assertThrows(IllegalStateException::class.java) { AlarmRules.snooze(alarm, 0) }
    }

    @Test
    fun snoozedOneShotCanFireAndCannotAcceptOldBroadcast() {
        val ringing = AlarmRules.markTriggered(AlarmRules.prepareEnabled(alarm, 1000))
        val snoozed = AlarmRules.snooze(ringing, 2000)
        assertFalse(AlarmRules.shouldTrigger(snoozed, 1000))
        assertTrue(AlarmRules.shouldTrigger(snoozed, 302000))
        val ringingAgain = AlarmRules.markTriggered(snoozed)
        assertTrue(ringingAgain.isRinging)
        assertFalse(ringingAgain.isEnabled)
        assertFalse(ringingAgain.isSnoozed)
        assertEquals(1000L, ringingAgain.occurrenceScheduledAtMillis)
        assertFalse(AlarmRules.shouldTrigger(ringingAgain, 302000))
    }

    @Test
    fun multipleSnoozesOnlyKeepLatestPendingTime() {
        val first = AlarmRules.snooze(alarm.copy(isRinging = true), 1000)
        val second = AlarmRules.snooze(AlarmRules.markTriggered(first), 302000)
        assertFalse(AlarmRules.shouldTrigger(second, first.scheduledAtMillis))
        assertTrue(AlarmRules.shouldTrigger(second, 602000))
    }

    @Test
    fun snoozeCanCrossMidnightWithoutChangingRepeatDays() {
        val beforeMidnight =
            ZonedDateTime.parse("2026-11-12T23:58:00Z[Europe/London]").toInstant().toEpochMilli()
        val snoozed =
            AlarmRules.snooze(
                alarm.copy(isRinging = true, repeatDays = setOf(DayOfWeek.THURSDAY)),
                beforeMidnight,
            )
        assertEquals(
            "2026-11-13T00:03:00Z",
            Instant.ofEpochMilli(snoozed.scheduledAtMillis).toString(),
        )
        assertEquals(setOf(DayOfWeek.THURSDAY), snoozed.repeatDays)
    }

    @Test
    fun completingOneShotNeverRepeats() {
        val completed = AlarmRules.completeOccurrence(alarm.copy(isRinging = true), thursdayMorning)
        assertFalse(completed.isEnabled)
        assertFalse(completed.isRinging)
        assertEquals(0L, completed.scheduledAtMillis)
    }

    @Test
    fun completingRepeatRegistersNextSelectedDay() {
        val ringing = alarm.copy(isEnabled = true, isRinging = true, repeatDays = weekdays)
        val nextOccurrence = AlarmRules.completeOccurrence(ringing, thursdayMorning.plusHours(2))
        assertTrue(nextOccurrence.isEnabled)
        assertFalse(nextOccurrence.isRinging)
        assertEquals(
            "2026-11-13T07:00:00Z",
            Instant.ofEpochMilli(nextOccurrence.scheduledAtMillis).toString(),
        )
        assertEquals(-1L, nextOccurrence.mathsStartedAtElapsedMillis)
        assertEquals(0, nextOccurrence.correctAnswerCount)
    }

    @Test
    fun clockRollbackCannotRescheduleSameOccurrence() {
        val originalTime = thursdayMorning.plusHours(1).toInstant().toEpochMilli()
        val ringing =
            alarm.copy(
                isEnabled = true,
                isRinging = true,
                repeatDays = weekdays,
                occurrenceScheduledAtMillis = originalTime,
            )
        val nextOccurrence = AlarmRules.completeOccurrence(ringing, thursdayMorning)
        assertEquals(
            "2026-11-13T07:00:00Z",
            Instant.ofEpochMilli(nextOccurrence.scheduledAtMillis).toString(),
        )
    }

    @Test
    fun disablingSnoozeClearsPendingDeliveryButKeepsDefinition() {
        val snoozed = AlarmRules.snooze(alarm.copy(isRinging = true, repeatDays = weekdays), 1000)
        val disabled = AlarmRules.markDismissed(snoozed)
        assertFalse(disabled.isEnabled)
        assertFalse(disabled.isSnoozed)
        assertEquals(0L, disabled.scheduledAtMillis)
        assertEquals(weekdays, disabled.repeatDays)
        assertFalse(AlarmRules.shouldTrigger(disabled, snoozed.scheduledAtMillis))
    }

    @Test
    fun editingOrReenablingReplacesSnoozeWithRegularSchedule() {
        val snoozed = AlarmRules.snooze(alarm.copy(isRinging = true), 1000)
        val updated = AlarmRules.prepareEnabled(snoozed, 999999)
        assertFalse(updated.isSnoozed)
        assertEquals(0L, updated.occurrenceScheduledAtMillis)
        assertFalse(AlarmRules.shouldTrigger(updated, snoozed.scheduledAtMillis))
    }

    @Test
    fun repeatDayChangeCountsAsUnsavedEdit() {
        assertTrue(Navigation.hasUnsavedChanges(alarm.copy(repeatDays = weekdays), alarm))
    }

    @Test
    fun repeatLabelsAreOrderedAndIncludeWeekend() {
        assertEquals(
            "Mon, Sat, Sun",
            RepeatDays.describe(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY)),
        )
        assertEquals("One time", RepeatDays.describe(emptySet()))
        assertEquals("Every day", RepeatDays.describe(DayOfWeek.values().toSet()))
    }
}
