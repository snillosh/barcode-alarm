package uk.co.barcodealarm

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class AlarmOverviewTest {
    private val now = ZonedDateTime.parse("2026-11-13T06:00:00Z[Europe/London]")
    private val nowMillis = now.toInstant().toEpochMilli()
    private val alarm = Alarm(1, 7, 0, "first", "QR_CODE", label = "Work")

    private fun scheduled(source: Alarm = alarm, at: ZonedDateTime = now.plusHours(1)): Alarm =
        AlarmRules.prepareEnabled(source, at.toInstant().toEpochMilli())

    @Test
    fun labelsTrimAndRoundTripWhenCreatedAndEdited() {
        val created = alarm.copy(label = AlarmOverview.cleanLabel("  Early shift  "))
        assertEquals(
            "Early shift",
            AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(created))).single().label,
        )
        val edited = created.copy(label = AlarmOverview.cleanLabel(" Gym "))
        assertEquals(
            "Gym",
            AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(edited))).single().label,
        )
        assertTrue(Navigation.hasUnsavedChanges(edited, created))
    }

    @Test
    fun blankLabelsUseDefault() {
        assertEquals("Alarm", AlarmOverview.displayLabel(alarm.copy(label = " \t ")))
        assertEquals("", AlarmOverview.cleanLabel("  "))
    }

    @Test
    fun labelsHaveFiftyCharacterLimit() {
        assertEquals(50, AlarmOverview.cleanLabel(" " + "a".repeat(100)).length)
        assertEquals(
            50,
            AlarmJsonCodec.decode(
                    AlarmJsonCodec.encode(listOf(alarm.copy(label = "a".repeat(100))))
                )
                .single()
                .label
                .length,
        )
    }

    @Test
    fun legacyAlarmsDefaultLabelWithoutChangingConfiguration() {
        val legacy =
            """[{"id":4,"hour":7,"minute":30,"code":"123","format":"QR_CODE","enabled":true,"due":999999,"sound":"content://tone"}]"""
        val decoded = AlarmJsonCodec.decode(legacy).single()
        assertEquals("", decoded.label)
        assertEquals("Alarm", AlarmOverview.displayLabel(decoded))
        assertTrue(decoded.isEnabled)
        assertEquals(999999L, decoded.scheduledAtMillis)
        assertEquals("123", decoded.barcodeValue)
        assertEquals("content://tone", decoded.soundUri)
    }

    @Test
    fun duplicatePreservesEveryConfigurationAndResourceReference() {
        val configured =
            alarm.copy(
                soundUri = "content://shared-tone",
                barcodeId = 11,
                firstBarcodeName = "Shampoo",
                secondBarcodeId = 12,
                secondBarcodeName = "Coffee",
                secondBarcodeValue = "second",
                secondBarcodeFormat = "CODE_128",
                scanIntervalMinutes = 3,
                verificationEnabled = true,
                verificationDelayMinutes = 9,
                verificationResponseMinutes = 4,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            )
        val duplicate = AlarmOverview.duplicate(configured, 2)
        // Complete equality catches new configurable fields accidentally omitted in future.
        assertEquals(configured.copy(id = 2), duplicate)
    }

    @Test
    fun duplicatesHaveIndependentIdentityAndNoRuntimeState() {
        val running =
            alarm.copy(
                isEnabled = true,
                isRinging = true,
                isSnoozed = true,
                scheduledAtMillis = 123,
                occurrenceScheduledAtMillis = 100,
                mathsStartedAtElapsedMillis = 2,
                correctAnswerCount = 8,
                playbackStartedAtElapsedMillis = 3,
            )
        val duplicate = AlarmOverview.duplicate(running, 8)
        assertEquals(alarm.copy(id = 8), duplicate)
        assertNotEquals(running.id, duplicate.id)
        assertFalse(duplicate.isEnabled)
        assertFalse(duplicate.isRinging)
        assertFalse(duplicate.isSnoozed)
        assertEquals(0L, duplicate.scheduledAtMillis)
        assertEquals(0L, duplicate.occurrenceScheduledAtMillis)
        assertEquals(-1L, duplicate.mathsStartedAtElapsedMillis)
        assertEquals(0, duplicate.correctAnswerCount)
        assertEquals(-1L, duplicate.playbackStartedAtElapsedMillis)
        assertTrue(running.isRinging)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCannotReuseOriginalIdentity() {
        AlarmOverview.duplicate(alarm, alarm.id)
    }

    @Test
    fun duplicateDoesNotMutateOrCopyActiveOccurrence() {
        val occurrence =
            Occurrence(
                1000000,
                alarm.id,
                alarm.copy(isRinging = true),
                stage = OccurrenceStage.VERIFY_RESPONSE,
                eligibleAt = 123,
                deadlineAt = 456,
            )
        val duplicate = AlarmOverview.duplicate(alarm, 2)
        assertEquals(1, occurrence.definitionId)
        assertEquals(OccurrenceStage.VERIFY_RESPONSE, occurrence.stage)
        assertEquals(456L, occurrence.deadlineAt)
        assertEquals(0L, duplicate.scheduledAtMillis)
    }

    @Test
    fun nextAlarmUsesActualTimestampRatherThanClockTime() {
        val tomorrowEarly = scheduled(alarm.copy(id = 2, hour = 5), now.plusDays(1).withHour(5))
        val todayLater = scheduled(alarm.copy(hour = 23), now.plusHours(17))
        assertEquals(
            todayLater,
            AlarmOverview.nextScheduled(listOf(tomorrowEarly, todayLater), nowMillis),
        )
    }

    @Test
    fun repeatingAlarmUsesExistingSchedulerCalculation() {
        val repeating = alarm.copy(repeatDays = setOf(DayOfWeek.MONDAY))
        val due =
            AlarmRules.calculateNextOccurrence(
                repeating.hour,
                repeating.minute,
                now,
                repeating.repeatDays,
            )
        val enabled = AlarmRules.prepareEnabled(repeating, due)
        assertEquals(enabled, AlarmOverview.nextScheduled(listOf(enabled), nowMillis))
        assertEquals(
            ZonedDateTime.parse("2026-11-16T07:00Z[Europe/London]").toInstant().toEpochMilli(),
            due,
        )
    }

    @Test
    fun disabledExpiredAndAlreadyFiredAlarmsAreExcluded() {
        val enabled = scheduled()
        assertNull(
            AlarmOverview.nextScheduled(
                listOf(
                    enabled.copy(isEnabled = false),
                    enabled.copy(scheduledAtMillis = nowMillis),
                    enabled.copy(scheduledAtMillis = nowMillis - 1),
                    enabled.copy(isRinging = true),
                ),
                nowMillis,
            )
        )
    }

    @Test
    fun pendingFollowupsCannotBecomeOrdinaryNextAlarms() {
        val pending =
            Occurrence(
                1000000,
                1,
                alarm.copy(isRinging = true),
                stage = OccurrenceStage.VERIFY_RESPONSE,
                deadlineAt = nowMillis + 1000,
            )
        assertNull(AlarmOverview.nextScheduled(listOf(pending.snapshot), nowMillis))
        assertEquals("No upcoming alarms", AlarmOverview.countdown(emptyList(), nowMillis))
    }

    @Test
    fun countdownRecalculatesAfterEditingDisablingDeletingAndDuplicating() {
        val initial = scheduled()
        assertEquals("1h remaining", AlarmOverview.countdown(listOf(initial), nowMillis))
        val edited = initial.copy(scheduledAtMillis = nowMillis + 42 * 60_000)
        assertEquals("42m remaining", AlarmOverview.countdown(listOf(edited), nowMillis))
        assertEquals(
            "No upcoming alarms",
            AlarmOverview.countdown(listOf(edited.copy(isEnabled = false)), nowMillis),
        )
        assertEquals("No upcoming alarms", AlarmOverview.countdown(emptyList(), nowMillis))
        assertEquals(
            "42m remaining",
            AlarmOverview.countdown(listOf(edited, AlarmOverview.duplicate(edited, 2)), nowMillis),
        )
        assertEquals("41m remaining", AlarmOverview.countdown(listOf(edited), nowMillis + 60_000))
    }

    @Test
    fun firedTimeDisappearsAtDeadlineWithoutRollingToUnscheduledTomorrow() {
        val enabled = scheduled()
        assertEquals(
            "No upcoming alarms",
            AlarmOverview.countdown(listOf(enabled), enabled.scheduledAtMillis),
        )
    }

    @Test
    fun springForwardUsesElapsedDurationAndLocalScheduledTime() {
        val before = ZonedDateTime.parse("2027-03-28T00:30Z[Europe/London]")
        val due = AlarmRules.calculateNextOccurrence(2, 30, before)
        val enabled = AlarmRules.prepareEnabled(alarm.copy(hour = 2, minute = 30), due)
        assertEquals(
            "1h remaining",
            AlarmOverview.countdown(listOf(enabled), before.toInstant().toEpochMilli()),
        )
        assertEquals(
            "Today at 02:30 · Work",
            AlarmOverview.scheduledDescription(enabled, before, true, Locale.UK),
        )
    }

    @Test
    fun autumnOverlapUsesScheduledInstantWithoutAddingAnExtraHour() {
        val before = ZonedDateTime.parse("2027-10-31T00:00+01:00[Europe/London]")
        val due = AlarmRules.calculateNextOccurrence(1, 30, before)
        val enabled = AlarmRules.prepareEnabled(alarm.copy(hour = 1, minute = 30), due)
        assertEquals(
            "1h 30m remaining",
            AlarmOverview.countdown(listOf(enabled), before.toInstant().toEpochMilli()),
        )
        assertEquals(
            "Today at 01:30 · Work",
            AlarmOverview.scheduledDescription(enabled, before, true, Locale.UK),
        )
    }

    @Test
    fun timezoneAndMidnightUseLocalCalendarDates() {
        val late = ZonedDateTime.parse("2026-11-13T23:45Z[Europe/London]")
        val enabled = scheduled(at = late.plusMinutes(30))
        assertEquals(
            "Tomorrow at 00:15 · Work",
            AlarmOverview.scheduledDescription(enabled, late, true, Locale.UK),
        )
        val tokyo = late.withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"))
        assertEquals(
            "Today at 09:15 · Work",
            AlarmOverview.scheduledDescription(enabled, tokyo, true, Locale.UK),
        )
        assertEquals(
            "30m remaining",
            AlarmOverview.countdown(listOf(enabled), tokyo.toInstant().toEpochMilli()),
        )
    }

    @Test
    fun laterDatesAndTwelveHourClockAreReadable() {
        val enabled = scheduled(at = now.plusDays(3).withHour(7))
        assertEquals(
            "Mon, 16 Nov at 7:00 AM · Work",
            AlarmOverview.scheduledDescription(enabled, now, false, Locale.US),
        )
    }

    @Test
    fun compactDurationsCoverMinutesHoursDaysAndBoundaries() {
        assertEquals("<1m", AlarmOverview.formatDuration(59_999))
        assertEquals("42m", AlarmOverview.formatDuration(42 * 60_000L))
        assertEquals("7h 24m", AlarmOverview.formatDuration((7 * 60 + 24) * 60_000L))
        assertEquals("2d 4h", AlarmOverview.formatDuration((2 * 24 + 4) * 3_600_000L))
        assertEquals("1d", AlarmOverview.formatDuration(24 * 3_600_000L))
        assertEquals("<1m", AlarmOverview.formatDuration(-1))
    }

    @Test
    fun enableMessageUsesActualScheduledInstantAndSharedDuration() {
        val enabled = scheduled().copy(scheduledAtMillis = nowMillis + (7 * 60 + 24) * 60_000L)
        assertEquals(
            "Alarm set for 7 hours and 24 minutes from now",
            AlarmOverview.enableConfirmation(enabled, nowMillis),
        )
        assertEquals(
            "Alarm set for 1 hour from now",
            AlarmOverview.enableConfirmation(scheduled(), nowMillis),
        )
        assertEquals(
            "Alarm set for less than a minute from now",
            AlarmOverview.enableConfirmation(
                enabled.copy(scheduledAtMillis = nowMillis + 1000),
                nowMillis,
            ),
        )
    }

    @Test
    fun failedSchedulingAndDisableCannotProduceSuccessMessage() {
        val scheduled = scheduled()
        // AlarmScheduler rolls a failed registration back to markDismissed and throws.
        assertNull(AlarmOverview.enableConfirmation(AlarmRules.markDismissed(scheduled), nowMillis))
        assertNull(AlarmOverview.enableConfirmation(alarm, nowMillis))
        assertNull(AlarmOverview.enableConfirmation(scheduled, scheduled.scheduledAtMillis))
    }

    @Test
    fun occurrenceSnapshotRetainsLabelAcrossDefinitionEditsAndRecovery() {
        val occurrence = Occurrence(1000000, alarm.id, alarm.copy(isRinging = true))
        val edited = alarm.copy(label = "Gym")
        val restored =
            OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(occurrence))).single()
        assertEquals("Work", AlarmOverview.displayLabel(restored.snapshot))
        assertEquals("Gym", AlarmOverview.displayLabel(edited))
    }
}
