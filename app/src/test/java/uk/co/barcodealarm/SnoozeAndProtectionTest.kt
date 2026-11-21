package uk.co.barcodealarm

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SnoozeAndProtectionTest {
    private val now = 1_000_000L
    private val elapsed = 10_000L

    private fun timer(snooze: Boolean = false, code: String = "", second: String = ""): Occurrence {
        val configuration =
            Alarm(
                1,
                0,
                0,
                code,
                "QR_CODE",
                canBeSnoozed = snooze,
                secondBarcodeValue = second,
                secondBarcodeFormat = "QR_CODE",
            )
        return TimerRules.start(TimerPreset(1, 600_000, configuration), 100, now, elapsed)
    }

    private fun ringing(snooze: Boolean = false, code: String = "", second: String = "") =
        timer(snooze, code, second).copy(stage = OccurrenceStage.INITIAL)

    @Test
    fun alarmDefaultsAndLegacyAlarmsKeepSnoozeEnabled() {
        assertTrue(Alarm(1, 7, 0, "", "").canBeSnoozed)
        val old = """[{"id":1,"hour":7,"minute":0,"code":"","format":""}]"""
        assertTrue(AlarmJsonCodec.decode(old).single().canBeSnoozed)
    }

    @Test
    fun newAndLegacyTimerPresetsDefaultSnoozeOff() {
        assertFalse(TimerPreset(1, 60000).configuration.canBeSnoozed)
        val legacy =
            """[{"id":1,"duration":60000,"configuration":"[{\"id\":1,\"hour\":0,\"minute\":0,\"code\":\"\",\"format\":\"\"}]"}]"""
        assertFalse(TimerJsonCodec.decode(legacy).single().configuration.canBeSnoozed)
    }

    @Test
    fun explicitSnoozeChoiceSurvivesBothPresetAndSnapshotCodecs() {
        for (enabled in listOf(false, true)) {
            val alarm = Alarm(1, 7, 0, "", "", canBeSnoozed = enabled)
            assertEquals(
                enabled,
                AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(alarm))).single().canBeSnoozed,
            )
            val preset = TimerPreset(1, 60000, alarm)
            assertEquals(
                enabled,
                TimerJsonCodec.decode(TimerJsonCodec.encode(listOf(preset)))
                    .single()
                    .configuration
                    .canBeSnoozed,
            )
            val run = ringing(enabled)
            assertEquals(
                enabled,
                OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(run)))
                    .single()
                    .snapshot
                    .canBeSnoozed,
            )
        }
    }

    @Test
    fun oldActiveTimerSnapshotsMigrateOffWithoutChangingExistingSnoozeDeadline() {
        val snoozed = OccurrenceRules.event(ringing(true), "snooze", now)
        val json = JSONArray(OccurrenceJsonCodec.encode(listOf(snoozed)))
        val snapshot = JSONArray(json.getJSONObject(0).getString("snapshot"))
        snapshot.getJSONObject(0).remove("canBeSnoozed")
        json.getJSONObject(0).put("snapshot", snapshot.toString())
        val restored = OccurrenceJsonCodec.decode(json.toString()).single()
        assertFalse(restored.snapshot.canBeSnoozed)
        assertEquals(snoozed.snoozeAt, restored.snoozeAt)
        assertTrue(restored.active)
    }

    @Test
    fun disabledSnoozeDoesNotAlterAnyRingingPhase() {
        for (stage in
            listOf(
                OccurrenceStage.INITIAL,
                OccurrenceStage.SECOND_RINGING,
                OccurrenceStage.VERIFY_RINGING,
            )) {
            val run = ringing().copy(stage = stage)
            assertFalse(run.canSnooze)
            assertEquals(run, OccurrenceRules.event(run, "snooze", now))
        }
        val alarm = ringing().copy(source = OccurrenceSource.ALARM)
        assertEquals(alarm, OccurrenceRules.event(alarm, "snooze", now))
    }

    @Test
    fun enabledSnoozeRetainsStageAndProgressForBothSources() {
        for (source in OccurrenceSource.values()) {
            val run =
                ringing(true, "A", "B")
                    .copy(
                        source = source,
                        stage = OccurrenceStage.SECOND_RINGING,
                        firstScanAt = 123,
                        snapshot = ringing(true, "A", "B").snapshot.copy(correctAnswerCount = 8),
                    )
            val snoozed = OccurrenceRules.event(run, "snooze", now)
            assertEquals(OccurrenceStage.SNOOZED, snoozed.stage)
            val resumed = OccurrenceRules.event(snoozed, "resume", snoozed.snoozeAt)
            assertEquals(OccurrenceStage.SECOND_RINGING, resumed.stage)
            assertEquals(123L, resumed.firstScanAt)
            assertEquals(8, resumed.snapshot.correctAnswerCount)
        }
    }

    @Test
    fun snoozedNormalTimerCanBeDismissedImmediatelyAndCancelsResume() {
        val snoozed = OccurrenceRules.event(ringing(true), "snooze", now)
        assertFalse(snoozed.audible)
        assertTrue(snoozed.active)
        val done = OccurrenceRules.normalDismiss(snoozed, now + 1)
        assertEquals(OccurrenceStage.COMPLETED, done.stage)
        assertTrue(OccurrenceRules.deadlines(done).isEmpty())
        assertEquals(done, OccurrenceRules.event(done, "resume", snoozed.snoozeAt))
    }

    @Test
    fun snoozedProtectedTimerCannotBypassBarcode() {
        val snoozed = OccurrenceRules.event(ringing(true, "A"), "snooze", now)
        assertEquals(snoozed, OccurrenceRules.normalDismiss(snoozed, now + 1))
        assertEquals(snoozed, OccurrenceRules.scan(snoozed, "wrong", "QR_CODE", now + 1, elapsed))
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(snoozed, "A", "QR_CODE", now + 1, elapsed).stage,
        )
    }

    @Test
    fun snoozedSecondStageStillRequiresOnlyTheSecondBarcode() {
        val second =
            ringing(true, "A", "B")
                .copy(
                    stage = OccurrenceStage.SECOND_RINGING,
                    firstScanAt = 123,
                    eligibleAt = now - 1,
                    eligibleElapsed = elapsed - 1,
                )
        val snoozed = OccurrenceRules.event(second, "snooze", now)
        assertEquals(snoozed, OccurrenceRules.scan(snoozed, "A", "QR_CODE", now + 1, elapsed))
        val done = OccurrenceRules.scan(snoozed, "B", "QR_CODE", now + 1, elapsed)
        assertEquals(OccurrenceStage.COMPLETED, done.stage)
        assertEquals(123L, done.firstScanAt)
    }

    @Test
    fun firstBarcodeDuringSnoozeEntersSilentSecondStageAndCancelsResume() {
        val snoozed = OccurrenceRules.event(ringing(true, "A", "B"), "snooze", now)
        val first = OccurrenceRules.scan(snoozed, "A", "QR_CODE", now + 1, elapsed)
        assertEquals(OccurrenceStage.SECOND_WAIT, first.stage)
        assertFalse(first.audible)
        assertEquals(setOf("ready", "timeout"), OccurrenceRules.deadlines(first).keys)
    }

    @Test
    fun mathsCompletionDuringSnoozeCompletesEntireTimer() {
        val snoozed = OccurrenceRules.event(ringing(true, "A", "B"), "snooze", now)
        assertEquals(OccurrenceStage.COMPLETED, OccurrenceRules.dismiss(snoozed, now + 1).stage)
        assertTrue(OccurrenceRules.deadlines(OccurrenceRules.dismiss(snoozed, now + 1)).isEmpty())
    }

    @Test
    fun countdownCannotBeDismissedOrSnoozedThroughRingingActions() {
        val countdown = timer(true)
        assertEquals(countdown, OccurrenceRules.normalDismiss(countdown, now))
        assertEquals(countdown, OccurrenceRules.dismiss(countdown, now))
        assertEquals(countdown, OccurrenceRules.event(countdown, "snooze", now))
    }

    @Test
    fun cancellationAllowedBeforeWindowButNotAtOrInsideBoundary() {
        val run = timer().copy(timerCancellationLockMinutes = 2)
        assertTrue(TimerRules.canCancel(run, now + 479999, elapsed + 479999))
        assertEquals(
            OccurrenceStage.CANCELLED,
            TimerRules.cancel(run, now + 479999, elapsed + 479999).stage,
        )
        for (passed in listOf(480000L, 599999L, 600000L)) {
            assertFalse(TimerRules.canCancel(run, now + passed, elapsed + passed))
            assertEquals(run, TimerRules.cancel(run, now + passed, elapsed + passed))
        }
    }

    @Test
    fun protectionOffKeepsExistingPreExpiryCancellation() {
        val run = timer().copy(timerCancellationLockMinutes = 0)
        assertTrue(TimerRules.canCancel(run, now + 599999, elapsed + 599999))
        assertFalse(TimerRules.canCancel(run, now + 600000, elapsed + 600000))
    }

    @Test
    fun protectionLongerThanTimerLocksFromStart() {
        assertFalse(
            TimerRules.canCancel(timer().copy(timerCancellationLockMinutes = 11), now, elapsed)
        )
    }

    @Test
    fun wallClockChangesCannotBypassProtection() {
        val run = timer().copy(timerCancellationLockMinutes = 2)
        assertFalse(TimerRules.canCancel(run, now - 999999, elapsed + 500000))
        assertFalse(TimerRules.canCancel(run, now + 999999, elapsed + 500000))
    }

    @Test
    fun lockSurvivesSerializationAndRebootWithoutChangingItsWindow() {
        val run = timer().copy(timerCancellationLockMinutes = 2)
        val restored = OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(run))).single()
        assertEquals(2, restored.timerCancellationLockMinutes)
        val rebooted = TimerRules.restore(restored, now + 500000, 100, true)
        assertFalse(TimerRules.canCancel(rebooted, now + 500000, 100))
    }

    @Test
    fun oldRunsKeepExistingCancellationRules() {
        val json = JSONArray(OccurrenceJsonCodec.encode(listOf(timer())))
        json.getJSONObject(0).remove("timerCancellationLockMinutes")
        val restored = OccurrenceJsonCodec.decode(json.toString()).single()
        assertEquals(0, restored.timerCancellationLockMinutes)
        assertTrue(TimerRules.canCancel(restored, now, elapsed))
    }

    @Test
    fun alarmDuplicationAndDirtyDraftChecksPreserveSnoozeChoice() {
        val alarm = Alarm(1, 7, 0, "", "", canBeSnoozed = false)
        assertFalse(AlarmOverview.duplicate(alarm, 2).canBeSnoozed)
        assertTrue(Navigation.hasUnsavedChanges(alarm.copy(canBeSnoozed = true), alarm))
    }

    @Test(expected = IllegalStateException::class)
    fun legacySnoozePathAlsoRejectsDisabledSnooze() {
        AlarmRules.snooze(Alarm(1, 7, 0, "", "", isRinging = true, canBeSnoozed = false), now)
    }
}
