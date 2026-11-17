package uk.co.barcodealarm

import org.junit.Assert.*
import org.junit.Test

/** Supplied clocks: no sleeps, Android services or camera required. */
class TimerRulesTest {
    private val now = 1_000_000L
    private val elapsed = 10_000L
    private val preset = TimerPreset(1, 15 * 60_000L, Alarm(1, 0, 0, "", "", label = "Bath"), 5)

    private fun start(config: TimerPreset = preset, id: Int = 100) =
        TimerRules.start(config, id, now, elapsed)

    private fun expire(run: Occurrence = start()) =
        OccurrenceRules.event(run, "expire", run.timerExpiresAt, run.timerExpiresElapsed)

    private fun protected(two: Boolean = false) =
        preset.copy(
            configuration =
                preset.configuration.copy(
                    barcodeId = 1,
                    barcodeValue = "A",
                    barcodeFormat = "QR_CODE",
                    secondBarcodeId = if (two) 2 else 0,
                    secondBarcodeValue = if (two) "B" else "",
                    secondBarcodeFormat = if (two) "QR_CODE" else "",
                    scanIntervalMinutes = 1,
                )
        )

    @Test
    fun createPresetRetainsLabelDurationAndConfiguration() {
        assertEquals(listOf(preset), TimerRules.savePreset(emptyList(), preset))
        assertEquals("Bath", TimerRules.label(preset))
    }

    @Test
    fun editPresetDoesNotEditExistingSnapshot() {
        val run = start()
        val edited =
            TimerRules.validate(
                preset.copy(
                    durationMillis = 60_000,
                    configuration = preset.configuration.copy(label = "Cooking"),
                )
            )
        assertEquals(15 * 60_000L, run.timerDurationMillis)
        assertEquals("Bath", run.snapshot.label)
        assertEquals("Cooking", edited.configuration.label)
        assertEquals(listOf(edited), TimerRules.savePreset(listOf(preset), edited))
    }

    @Test
    fun presetPersistenceRoundTripsAllSettings() {
        val original =
            protected(true)
                .copy(
                    configuration = protected(true).configuration.copy(soundUri = "content://tone")
                )
        assertEquals(
            listOf(original),
            TimerJsonCodec.decode(TimerJsonCodec.encode(listOf(original))),
        )
    }

    @Test
    fun labelsHaveSameLengthAndWhitespaceConventionsAsAlarms() {
        assertEquals(
            "Bath",
            TimerRules.label(
                preset.copy(configuration = preset.configuration.copy(label = " Bath "))
            ),
        )
        assertEquals(
            "Timer",
            TimerRules.label(preset.copy(configuration = preset.configuration.copy(label = " "))),
        )
        assertEquals(
            50,
            TimerRules.validate(
                    preset.copy(configuration = preset.configuration.copy(label = "a".repeat(100)))
                )
                .configuration
                .label
                .length,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDurationRejected() {
        TimerRules.validate(preset.copy(durationMillis = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDurationRejected() {
        TimerRules.validate(preset.copy(durationMillis = -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun subsecondDurationRejected() {
        TimerRules.validate(preset.copy(durationMillis = 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveDurationRejected() {
        TimerRules.validate(preset.copy(durationMillis = TimerRules.MAX_DURATION_MILLIS + 1))
    }

    @Test
    fun supportedDurationBoundariesAreValid() {
        assertEquals(1000L, TimerRules.validate(preset.copy(durationMillis = 1000)).durationMillis)
        assertEquals(
            TimerRules.MAX_DURATION_MILLIS,
            TimerRules.validate(preset.copy(durationMillis = TimerRules.MAX_DURATION_MILLIS))
                .durationMillis,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeReminderRejected() {
        TimerRules.validate(preset.copy(reminderMinutes = -1))
    }

    @Test
    fun startHasIndependentIdAndExactExpiration() {
        val run = start()
        assertEquals(100, run.id)
        assertEquals(preset.id, run.definitionId)
        assertEquals(OccurrenceSource.TIMER, run.source)
        assertEquals(OccurrenceStage.COUNTDOWN, run.stage)
        assertEquals(now, run.timerStartedAt)
        assertEquals(now + preset.durationMillis, run.timerExpiresAt)
        assertEquals(elapsed + preset.durationMillis, run.timerExpiresElapsed)
        assertEquals(run.timerExpiresAt, OccurrenceRules.deadlines(run)["expire"])
        assertNotEquals(run.id, start(id = 101).id)
    }

    @Test
    fun remainingUsesMonotonicTimeDespiteWallClockChange() {
        val run = start()
        assertEquals(14 * 60_000L, TimerRules.remaining(run, now + 99999999, elapsed + 60_000))
        assertEquals(14 * 60_000L, TimerRules.remaining(run, now - 99999999, elapsed + 60_000))
    }

    @Test
    fun processAndActivityRecreationDoNotResetCountdown() {
        val run = start()
        val restored = OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(run))).single()
        assertEquals(run, restored)
        assertEquals(12 * 60_000L, TimerRules.remaining(restored, now + 180000, elapsed + 180000))
        assertEquals(OccurrenceRules.deadlines(run), OccurrenceRules.deadlines(restored))
    }

    @Test
    fun countdownNeverStartsPlaybackBeforeExpiry() {
        val run = start()
        assertFalse(run.active)
        assertFalse(run.audible)
        assertEquals(run, OccurrenceRules.event(run, "expire", now + 1, elapsed + 1))
        assertEquals(run, OccurrenceRules.normalDismiss(run, now))
    }

    @Test
    fun cancellationRemovesExpirationAndRemindersWithoutDeletingPreset() {
        val cancelled = TimerRules.cancel(start(), now + 1000, elapsed + 1000)
        assertEquals(OccurrenceStage.CANCELLED, cancelled.stage)
        assertTrue(OccurrenceRules.deadlines(cancelled).isEmpty())
        assertEquals("Bath", TimerRules.label(preset))
        assertEquals(cancelled, TimerRules.cancel(cancelled, now + 2000, elapsed + 2000))
        assertEquals(
            cancelled,
            OccurrenceRules.event(cancelled, "expire", now + 9999999, elapsed + 9999999),
        )
    }

    @Test
    fun cancellationAtOrAfterDeadlineCannotBypassDismissal() {
        val run = start(protected())
        assertEquals(run, TimerRules.cancel(run, run.timerExpiresAt, run.timerExpiresElapsed))
        val ringing = expire(run)
        assertEquals(
            ringing,
            TimerRules.cancel(ringing, run.timerExpiresAt + 1, run.timerExpiresElapsed + 1),
        )
    }

    @Test
    fun duplicateExpirationIsHarmless() {
        val ringing = expire()
        assertTrue(ringing.audible)
        assertEquals(
            ringing,
            OccurrenceRules.event(
                ringing,
                "expire",
                ringing.timerExpiresAt + 1,
                ringing.timerExpiresElapsed + 1,
            ),
        )
    }

    @Test
    fun remindersOccurAtFiveAndTenMinutesOnly() {
        var run = start()
        assertEquals(now + 300000, OccurrenceRules.deadlines(run)["reminder"])
        run = OccurrenceRules.event(run, "reminder", now + 300000, elapsed + 300000)
        assertEquals(now + 600000, OccurrenceRules.deadlines(run)["reminder"])
        run = OccurrenceRules.event(run, "reminder", now + 600000, elapsed + 600000)
        assertFalse(OccurrenceRules.deadlines(run).containsKey("reminder"))
        assertEquals(now + 900000, OccurrenceRules.deadlines(run)["expire"])
    }

    @Test
    fun reminderAtExactExpirationIsNeverScheduledOrPlayed() {
        val run = start(preset.copy(durationMillis = 300000))
        assertNull(TimerRules.nextReminderOffset(run))
        assertEquals(
            run,
            OccurrenceRules.event(run, "reminder", run.timerExpiresAt, run.timerExpiresElapsed),
        )
    }

    @Test
    fun intervalLongerThanDurationProducesNoReminders() {
        assertNull(TimerRules.nextReminderOffset(start(preset.copy(reminderMinutes = 30))))
    }

    @Test
    fun disabledRemindersProduceNoEvents() {
        assertEquals(
            setOf("expire"),
            OccurrenceRules.deadlines(start(preset.copy(reminderMinutes = 0))).keys,
        )
    }

    @Test
    fun reminderCannotRingOrRequireAcknowledgement() {
        val reminded = OccurrenceRules.event(start(), "reminder", now + 300000, elapsed + 300000)
        assertEquals(OccurrenceStage.COUNTDOWN, reminded.stage)
        assertFalse(reminded.audible)
        assertFalse(reminded.active)
    }

    @Test
    fun duplicateReminderAndPrematureReminderDoNothing() {
        val run = start()
        assertEquals(run, OccurrenceRules.event(run, "reminder", now, elapsed))
        val reminded = OccurrenceRules.event(run, "reminder", now + 300000, elapsed + 300000)
        assertEquals(
            reminded,
            OccurrenceRules.event(reminded, "reminder", now + 300001, elapsed + 300001),
        )
    }

    @Test
    fun lateReminderSkipsBacklogWithoutMovingExpiration() {
        val run = start(preset.copy(durationMillis = 3600000, reminderMinutes = 1))
        val reminded = OccurrenceRules.event(run, "reminder", now + 300000, elapsed + 300000)
        assertEquals(5, reminded.reminderIndex)
        assertEquals(now + 360000, OccurrenceRules.deadlines(reminded)["reminder"])
        assertEquals(run.timerExpiresAt, reminded.timerExpiresAt)
    }

    @Test
    fun ordinaryTimerSupportsNormalDismissalAndNoVerification() {
        val done = OccurrenceRules.normalDismiss(expire(), now + 900000)
        assertEquals(OccurrenceStage.COMPLETED, done.stage)
        assertTrue(OccurrenceRules.deadlines(done).isEmpty())
    }

    @Test
    fun singleBarcodeTimerRejectsOrdinaryDismissAndWrongCode() {
        val ringing = expire(start(protected()))
        assertEquals(ringing, OccurrenceRules.normalDismiss(ringing, now + 900000))
        assertEquals(
            ringing,
            OccurrenceRules.scan(ringing, "wrong", "QR_CODE", now + 900000, elapsed + 900000),
        )
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(ringing, "A", "QR_CODE", now + 900000, elapsed + 900000).stage,
        )
    }

    @Test
    fun twoBarcodeTimerKeepsEligibilityAndFiveMinuteDeadline() {
        val run = expire(start(protected(true)))
        val first = OccurrenceRules.scan(run, "A", "QR_CODE", now + 900000, elapsed + 900000)
        assertFalse(first.audible)
        assertEquals(OccurrenceStage.SECOND_WAIT, first.stage)
        assertEquals(now + 960000, first.eligibleAt)
        assertEquals(first.eligibleAt + 300000, first.deadlineAt)
        assertEquals(
            first,
            OccurrenceRules.scan(
                first,
                "B",
                "QR_CODE",
                first.eligibleAt - 1,
                first.eligibleElapsed - 1,
            ),
        )
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(first, "B", "QR_CODE", first.eligibleAt, first.eligibleElapsed)
                .stage,
        )
    }

    @Test
    fun secondBarcodeTimeoutRestartsAtFullVolumeWithoutFirstAgain() {
        val first =
            OccurrenceRules.scan(
                expire(start(protected(true))),
                "A",
                "QR_CODE",
                now + 900000,
                elapsed + 900000,
            )
        val escalated = OccurrenceRules.event(first, "timeout", first.deadlineAt)
        assertEquals(OccurrenceStage.SECOND_RINGING, escalated.stage)
        assertTrue(escalated.fullVolume)
        assertEquals(first.firstScanAt, escalated.firstScanAt)
        assertEquals(
            escalated,
            OccurrenceRules.scan(
                escalated,
                "A",
                "QR_CODE",
                first.deadlineAt,
                first.eligibleElapsed + 300000,
            ),
        )
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(
                    escalated,
                    "B",
                    "QR_CODE",
                    first.deadlineAt,
                    first.eligibleElapsed + 300000,
                )
                .stage,
        )
    }

    @Test
    fun mathsCompletesEntireTimerBeforeOrAfterFirstScan() {
        val ringing = expire(start(protected(true)))
        val first = OccurrenceRules.scan(ringing, "A", "QR_CODE", now + 900000, elapsed + 900000)
        for (run in
            listOf(ringing, first, OccurrenceRules.event(first, "timeout", first.deadlineAt))) {
            val done = OccurrenceRules.dismiss(run, now + 2000000)
            assertEquals(OccurrenceStage.COMPLETED, done.stage)
            assertTrue(OccurrenceRules.deadlines(done).isEmpty())
        }
    }

    @Test
    fun timerSourceCannotVerifyEvenWithMalformedSnapshot() {
        val ringing = expire().copy(snapshot = expire().snapshot.copy(verificationEnabled = true))
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.dismiss(ringing, now + 900000).stage,
        )
        assertFalse(
            start(
                    preset.copy(
                        configuration = preset.configuration.copy(verificationEnabled = true)
                    )
                )
                .snapshot
                .verificationEnabled
        )
    }

    @Test
    fun twoTimersAndNormalAlarmHaveIndependentIdentityAndState() {
        val first = start()
        val second = start(preset.copy(id = 2), 101)
        val alarm = Occurrence(102, 1, Alarm(102, 7, 0, "", "", isRinging = true))
        val cancelled = TimerRules.cancel(first, now + 1000, elapsed + 1000)
        assertEquals(OccurrenceStage.CANCELLED, cancelled.stage)
        assertEquals(OccurrenceStage.COUNTDOWN, second.stage)
        assertTrue(alarm.audible)
        assertNotEquals(first.source, alarm.source)
        val ringing = listOf(expire(second), alarm)
        assertEquals(2, ringing.count { it.audible })
    }

    @Test
    fun simultaneousExpirationsLoseNeitherOccurrence() {
        val first = expire(start())
        val second = expire(start(preset.copy(id = 2), 101))
        val recovered =
            OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(first, second)))
        assertEquals(setOf(100, 101), recovered.filter { it.audible }.map { it.id }.toSet())
        assertTrue(recovered.all { it.source == OccurrenceSource.TIMER })
    }

    @Test
    fun rebootKeepsFutureRemainingInsteadOfRestartingDuration() {
        val run = start()
        val restored = TimerRules.restore(run, now + 180000, 500, true)
        assertEquals(12 * 60_000L, TimerRules.remaining(restored, now + 180000, 500))
        assertEquals(run.timerStartedAt, restored.timerStartedAt)
        assertEquals(run.timerExpiresAt, restored.timerExpiresAt)
    }

    @Test
    fun overdueRebootSchedulesOneImmediateExpirationAndNoReminders() {
        val restored = TimerRules.restore(start(), now + 1000000, 500, true)
        assertEquals(0L, TimerRules.remaining(restored, now + 1000000, 500))
        assertEquals(setOf("expire"), OccurrenceRules.deadlines(restored).keys)
        assertEquals(
            OccurrenceStage.INITIAL,
            OccurrenceRules.event(restored, "expire", now + 1000000, 500).stage,
        )
    }

    @Test
    fun clockChangeRebasesWallDeadlinesWithoutChangingMonotonicCountdown() {
        val restored = TimerRules.restore(start(), now + 9999999, elapsed + 60000, false)
        assertEquals(14 * 60_000L, TimerRules.remaining(restored, now + 9999999, elapsed + 60000))
        assertEquals(now + 9999999 + 14 * 60_000L, restored.timerExpiresAt)
    }

    @Test
    fun restorationSkipsMissedChimesAndPreservesNextFutureReminder() {
        val restored = TimerRules.restore(start(), now + 420000, 500, true)
        assertEquals(1, restored.reminderIndex)
        assertEquals(now + 600000, OccurrenceRules.deadlines(restored)["reminder"])
    }

    @Test
    fun completedAndCancelledRunsPersistAsReadyAndCanRestartFresh() {
        val done = OccurrenceRules.normalDismiss(expire(), now + 900000)
        val cancelled = TimerRules.cancel(start(id = 101), now, elapsed)
        val recovered =
            OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(done, cancelled)))
        assertTrue(recovered.all { it.terminal })
        val fresh = TimerRules.start(preset, 102, now + 2000000, elapsed + 2000000)
        assertEquals(OccurrenceStage.COUNTDOWN, fresh.stage)
        assertEquals(0, fresh.reminderIndex)
        assertEquals(
            preset.durationMillis,
            TimerRules.remaining(fresh, now + 2000000, elapsed + 2000000),
        )
    }

    @Test
    fun deletingOnePresetLeavesOtherConfigurationsAndSharedResources() {
        val first =
            protected()
                .copy(configuration = protected().configuration.copy(soundUri = "content://tone"))
        val second = first.copy(id = 2)
        val saved =
            TimerJsonCodec.decode(
                TimerJsonCodec.encode(TimerRules.deletePreset(listOf(first, second), first.id))
            )
        assertEquals(1, saved.size)
        assertEquals(1, saved.single().configuration.barcodeId)
        assertEquals("content://tone", saved.single().configuration.soundUri)
    }

    @Test
    fun timerCountdownFormatsSecondsHoursAndSevenDays() {
        assertEquals("00:01", TimerRules.countdown(1))
        assertEquals("00:00", TimerRules.countdown(-1))
        assertEquals("08:42", TimerRules.countdown(522000))
        assertEquals("1:00:01", TimerRules.countdown(3601000))
        assertEquals("168:00:00", TimerRules.countdown(TimerRules.MAX_DURATION_MILLIS))
    }

    @Test
    fun timersReuseExistingSnoozeAndMathsRulesAfterExpiry() {
        val ringing = expire(start(protected()))
        val snoozed = OccurrenceRules.event(ringing, "snooze", now + 900000)
        assertEquals(OccurrenceStage.SNOOZED, snoozed.stage)
        assertFalse(snoozed.snapshot.verificationEnabled)
        val resumed = OccurrenceRules.event(snoozed, "resume", snoozed.snoozeAt)
        assertEquals(OccurrenceStage.INITIAL, resumed.stage)
        assertEquals("A", resumed.snapshot.barcodeValue)
        assertFalse(AlarmRules.canDismissWithMaths(resumed.snapshot, elapsed))
    }

    @Test
    fun definitionIdentityIsScopedBySourceForCancellationAndDeletion() {
        val timer = start()
        val alarm = Occurrence(101, preset.id, Alarm(101, 7, 0, "", ""))
        assertTrue(timer.belongsTo(OccurrenceSource.TIMER, preset.id))
        assertFalse(timer.belongsTo(OccurrenceSource.ALARM, preset.id))
        assertTrue(alarm.belongsTo(OccurrenceSource.ALARM, preset.id))
        assertFalse(alarm.belongsTo(OccurrenceSource.TIMER, preset.id))
    }

    @Test
    fun bootIdentitySurvivesProcessRecreation() {
        val original = start().copy(timerBootCount = 42)
        val restored =
            OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(original))).single()
        assertEquals(42, restored.timerBootCount)
        assertEquals(original, restored)
    }

    @Test
    fun timerConfigurationRemovesAlarmRuntimeRepeatsAndVerification() {
        val configured =
            preset.copy(
                configuration =
                    preset.configuration.copy(
                        isEnabled = true,
                        isRinging = true,
                        isSnoozed = true,
                        scheduledAtMillis = 1234,
                        occurrenceScheduledAtMillis = 1234,
                        mathsStartedAtElapsedMillis = 20,
                        correctAnswerCount = 8,
                        playbackStartedAtElapsedMillis = 50,
                        verificationEnabled = true,
                        repeatDays = setOf(java.time.DayOfWeek.MONDAY),
                        soundUri = "content://shared-tone",
                    )
            )
        val timer = start(configured)
        assertTrue(timer.snapshot.isRinging)
        assertFalse(timer.snapshot.isEnabled)
        assertFalse(timer.snapshot.isSnoozed)
        assertFalse(timer.snapshot.verificationEnabled)
        assertTrue(timer.snapshot.repeatDays.isEmpty())
        assertEquals(0L, timer.snapshot.scheduledAtMillis)
        assertEquals(0L, timer.snapshot.occurrenceScheduledAtMillis)
        assertEquals(-1L, timer.snapshot.mathsStartedAtElapsedMillis)
        assertEquals(0, timer.snapshot.correctAnswerCount)
        assertEquals(-1L, timer.snapshot.playbackStartedAtElapsedMillis)
        assertEquals("content://shared-tone", timer.snapshot.soundUri)
    }
}
