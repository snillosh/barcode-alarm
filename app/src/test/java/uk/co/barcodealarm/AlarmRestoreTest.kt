package uk.co.barcodealarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRestoreTest {
    private val now = ZonedDateTime.parse("2026-11-12T08:00:00Z[Europe/London]")
    private val repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
    private val alarm =
        Alarm(
            1,
            7,
            0,
            "code",
            "QR_CODE",
            isEnabled = true,
            scheduledAtMillis = now.minusHours(1).toInstant().toEpochMilli(),
        )

    private fun restore(value: Alarm, clockChange: Boolean = false, boot: Boolean = true) =
        AlarmRules.restoreAfterSystemChange(value, now, clockChange, boot)

    @Test
    fun missedOneShotIsDisabled() {
        assertFalse(restore(alarm).isEnabled)
    }

    @Test
    fun missedRepeatSkipsPastDaysAndSchedulesFuture() {
        val restored = restore(alarm.copy(repeatDays = repeatDays))
        assertTrue(restored.isEnabled)
        assertEquals(
            "2026-11-14T07:00:00Z",
            Instant.ofEpochMilli(restored.scheduledAtMillis).toString(),
        )
    }

    @Test
    fun futureOneShotKeepsItsOriginalTimestamp() {
        val future = alarm.copy(scheduledAtMillis = now.plusHours(2).toInstant().toEpochMilli())
        assertEquals(future, restore(future, clockChange = true))
    }

    @Test
    fun futureSnoozeKeepsItsTimestampAndMathsProgress() {
        val snooze =
            alarm.copy(
                isSnoozed = true,
                repeatDays = repeatDays,
                scheduledAtMillis = now.plusMinutes(3).toInstant().toEpochMilli(),
                correctAnswerCount = 9,
                mathsStartedAtElapsedMillis = 100,
            )
        assertEquals(snooze, restore(snooze, clockChange = true, boot = false))
    }

    @Test
    fun rebootResetsPendingSnoozeMathsButKeepsItsDeliveryTime() {
        val snooze =
            alarm.copy(
                isSnoozed = true,
                scheduledAtMillis = now.plusMinutes(3).toInstant().toEpochMilli(),
                mathsStartedAtElapsedMillis = 100,
                correctAnswerCount = 9,
            )
        assertEquals(
            snooze.copy(mathsStartedAtElapsedMillis = -1, correctAnswerCount = 0),
            restore(snooze),
        )
    }

    @Test
    fun missedSnoozeForRepeatReturnsToRegularDays() {
        val restored =
            restore(alarm.copy(isSnoozed = true, repeatDays = repeatDays, correctAnswerCount = 9))
        assertTrue(restored.isEnabled)
        assertFalse(restored.isSnoozed)
        assertEquals(0, restored.correctAnswerCount)
    }

    @Test
    fun missedOneShotSnoozeIsDisabled() {
        assertFalse(restore(alarm.copy(isSnoozed = true)).isEnabled)
    }

    @Test
    fun rebootOfInterruptedRepeatRestoresNextDayWithoutStartingPlayback() {
        val ringing =
            alarm.copy(
                isRinging = true,
                scheduledAtMillis = 0,
                repeatDays = repeatDays,
                mathsStartedAtElapsedMillis = 123,
                correctAnswerCount = 5,
            )
        val restored = restore(ringing)
        assertTrue(restored.isEnabled)
        assertFalse(restored.isRinging)
        assertEquals(
            "2026-11-14T07:00:00Z",
            Instant.ofEpochMilli(restored.scheduledAtMillis).toString(),
        )
        assertEquals(-1L, restored.mathsStartedAtElapsedMillis)
        assertEquals(0, restored.correctAnswerCount)
    }

    @Test
    fun rebootOfInterruptedOneShotKeepsItsExistingResumeOnOpenBehaviour() {
        val ringing =
            alarm.copy(
                isEnabled = false,
                isRinging = true,
                scheduledAtMillis = 0,
                mathsStartedAtElapsedMillis = 123,
                correctAnswerCount = 5,
            )
        assertEquals(
            ringing.copy(mathsStartedAtElapsedMillis = -1, correctAnswerCount = 0),
            restore(ringing),
        )
    }

    @Test
    fun clockChangeDoesNotRescheduleAnActiveAlarm() {
        val ringing = alarm.copy(isRinging = true, scheduledAtMillis = 0, repeatDays = repeatDays)
        assertEquals(ringing, restore(ringing, clockChange = true, boot = false))
    }

    @Test
    fun timezoneChangeRecalculatesRegularRepeatUsingLocalTime() {
        val future =
            alarm.copy(
                repeatDays = repeatDays,
                scheduledAtMillis = now.plusDays(2).plusHours(3).toInstant().toEpochMilli(),
            )
        val restored = restore(future, clockChange = true, boot = false)
        assertEquals(
            "2026-11-14T07:00:00Z",
            Instant.ofEpochMilli(restored.scheduledAtMillis).toString(),
        )
    }

    @Test
    fun ordinaryBootKeepsAStillFutureRepeatTimestamp() {
        val future =
            alarm.copy(
                repeatDays = repeatDays,
                scheduledAtMillis = now.plusDays(2).toInstant().toEpochMilli(),
            )
        assertEquals(future, restore(future))
    }

    @Test
    fun disabledRepeatDoesNotEnableItselfAfterReboot() {
        val disabled = alarm.copy(isEnabled = false, repeatDays = repeatDays)
        assertEquals(disabled, restore(disabled))
    }
}
