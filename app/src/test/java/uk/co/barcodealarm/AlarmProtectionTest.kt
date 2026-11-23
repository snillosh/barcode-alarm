package uk.co.barcodealarm

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class AlarmProtectionTest {
    private val now = 1_000_000L
    private val alarm = Alarm(1, 7, 0, "", "", isEnabled = true, scheduledAtMillis = now + 600_000)

    private fun reason(
        value: Alarm = alarm,
        runs: List<Occurrence> = emptyList(),
        time: Long = now,
    ) = AlarmProtectionRules.reason(value, runs, value.id, time)

    private fun snoozed(code: String = "", second: String = "") =
        Occurrence(
            1000000,
            1,
            Alarm(
                1000000,
                7,
                0,
                code,
                "QR_CODE",
                label = "Work",
                secondBarcodeValue = second,
                secondBarcodeFormat = "QR_CODE",
            ),
            stage = OccurrenceStage.SNOOZED,
            snoozeAt = now + 300_000,
        )

    @Test
    fun ordinaryFarFutureAndDisabledAlarmsRemainEditable() {
        assertNull(reason())
        assertNull(reason(alarm.copy(isEnabled = false, scheduledAtMillis = now + 1)))
    }

    @Test
    fun exactFinalWindowBoundaryAndPendingOverdueDeliveryAreProtected() {
        assertNull(reason(alarm.copy(scheduledAtMillis = now + 300_001)))
        for (due in listOf(now + 300_000, now + 1, now, now - 1)) {
            assertNotNull(reason(alarm.copy(scheduledAtMillis = due)))
        }
    }

    @Test
    fun perDeliveryProtectionControlsTheBoundary() {
        assertNull(
            reason(alarm.copy(scheduledAtMillis = now + 120_001, alarmProtectionMinutes = 2))
        )
        assertNotNull(
            reason(alarm.copy(scheduledAtMillis = now + 120_000, alarmProtectionMinutes = 2))
        )
    }

    @Test
    fun snoozedOneShotRemainsProtectedWithDisabledDefinition() {
        assertNotNull(
            reason(alarm.copy(isEnabled = false, scheduledAtMillis = 0), listOf(snoozed()))
        )
    }

    @Test
    fun allUnfinishedAlarmPhasesBlockDeletionAndCancellation() {
        for (stage in
            listOf(
                OccurrenceStage.INITIAL,
                OccurrenceStage.SECOND_WAIT,
                OccurrenceStage.SECOND_READY,
                OccurrenceStage.SECOND_RINGING,
                OccurrenceStage.SNOOZED,
                OccurrenceStage.VERIFY_DELAY,
                OccurrenceStage.VERIFY_RESPONSE,
                OccurrenceStage.VERIFY_RINGING,
            )) {
            assertNotNull(
                stage.name,
                reason(alarm.copy(isEnabled = false), listOf(snoozed().copy(stage = stage))),
            )
        }
    }

    @Test
    fun timerIdsAndOtherAlarmIdsCannotProtectUnrelatedDefinitions() {
        assertNull(reason(runs = listOf(snoozed().copy(source = OccurrenceSource.TIMER))))
        assertNull(reason(runs = listOf(snoozed().copy(definitionId = 2))))
    }

    @Test
    fun terminalOccurrencesDoNotBlockDefinitionDeletion() {
        for (stage in listOf(OccurrenceStage.COMPLETED, OccurrenceStage.CANCELLED)) assertNull(
            reason(runs = listOf(snoozed().copy(stage = stage)))
        )
    }

    @Test
    fun legacyExecutionFlagsRemainProtected() {
        assertNotNull(reason(alarm.copy(isSnoozed = true)))
        assertNotNull(reason(alarm.copy(isRinging = true)))
    }

    @Test
    fun protectionSurvivesJsonAndOldAlarmsReceiveFiveMinutes() {
        val configured = alarm.copy(alarmProtectionMinutes = 12)
        assertEquals(
            configured,
            AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(configured))).single(),
        )
        assertEquals(
            5,
            AlarmJsonCodec.decode("""[{"id":1,"hour":7,"minute":0,"code":"","format":""}]""")
                .single()
                .alarmProtectionMinutes,
        )
    }

    @Test
    fun nextAlarmIncludesSnoozeEvenWhenDefinitionIsDisabled() {
        val next =
            AlarmOverview.nextScheduled(
                listOf(alarm.copy(isEnabled = false)),
                now,
                listOf(snoozed()),
            )!!
        assertEquals(now + 300_000, next.scheduledAtMillis)
        assertTrue(next.isSnoozed)
        assertEquals("5m remaining", AlarmOverview.countdown(emptyList(), now, listOf(snoozed())))
    }

    @Test
    fun snoozeIsListedAheadOfTomorrowsRepeatAndDoesNotRewriteIt() {
        val tomorrow = alarm.copy(scheduledAtMillis = now + 86_400_000)
        val next = AlarmOverview.nextScheduled(listOf(tomorrow), now, listOf(snoozed()))!!
        assertTrue(next.isSnoozed)
        assertEquals(now + 300_000, next.scheduledAtMillis)
        assertEquals(now + 86_400_000, tomorrow.scheduledAtMillis)
    }

    @Test
    fun aSoonerOrdinaryAlarmStillWinsOverSnooze() {
        val next = alarm.copy(scheduledAtMillis = now + 60_000)
        assertEquals(next, AlarmOverview.nextScheduled(listOf(next), now, listOf(snoozed())))
    }

    @Test
    fun timerSnoozesAndVerificationDeadlinesAreNotOrdinaryAlarmDeliveries() {
        val runs =
            listOf(
                snoozed().copy(source = OccurrenceSource.TIMER),
                snoozed().copy(stage = OccurrenceStage.VERIFY_DELAY),
            )
        assertNull(AlarmOverview.nextScheduled(emptyList(), now, runs))
        assertEquals(
            "Wake-up verification scheduled",
            AlarmOverview.countdown(emptyList(), now, runs),
        )
    }

    @Test
    fun expiredSnoozeStillShowsActiveStatusUntilResumeArrives() {
        assertEquals(
            "Snoozed alarm due now",
            AlarmOverview.countdown(emptyList(), now + 300_001, listOf(snoozed())),
        )
        assertEquals(
            "Alarm ringing",
            AlarmOverview.countdown(
                emptyList(),
                now,
                listOf(snoozed().copy(stage = OccurrenceStage.INITIAL)),
            ),
        )
    }

    @Test
    fun snoozedAlarmsStayActiveButSilentAndAllowProperDismissal() {
        val run = snoozed()
        assertTrue(run.active)
        assertFalse(run.audible)
        val done = OccurrenceRules.normalDismiss(run, now)
        assertEquals(OccurrenceStage.COMPLETED, done.stage)
        assertTrue(OccurrenceRules.deadlines(done).isEmpty())
        assertEquals(done, OccurrenceRules.event(done, "resume", run.snoozeAt))
    }

    @Test
    fun barcodeProtectedSnoozeCannotUseOrdinaryDismissal() {
        val run = snoozed("A")
        assertEquals(run, OccurrenceRules.normalDismiss(run, now))
        assertEquals(run, OccurrenceRules.scan(run, "wrong", "QR_CODE", now, 100))
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(run, "A", "QR_CODE", now, 100).stage,
        )
    }

    @Test
    fun secondStageSnoozePreservesBarcodeProgress() {
        val run =
            snoozed("A", "B")
                .copy(
                    resumeStage = OccurrenceStage.SECOND_RINGING,
                    firstScanAt = 123,
                    eligibleAt = now - 1,
                    eligibleElapsed = 99,
                )
        assertEquals(run, OccurrenceRules.scan(run, "A", "QR_CODE", now, 100))
        val done = OccurrenceRules.scan(run, "B", "QR_CODE", now, 100)
        assertEquals(OccurrenceStage.COMPLETED, done.stage)
        assertEquals(123L, done.firstScanAt)
    }

    @Test
    fun mathsAndVerificationStillFollowProperAlarmCompletionRules() {
        val run =
            snoozed("A").copy(snapshot = snoozed("A").snapshot.copy(verificationEnabled = true))
        val dismissed = OccurrenceRules.dismiss(run, now)
        assertEquals(OccurrenceStage.VERIFY_DELAY, dismissed.stage)
        assertEquals(setOf("verify", "failed"), OccurrenceRules.deadlines(dismissed).keys)
    }

    @Test
    fun snoozeDescriptionUsesItsLocalDeadlineAndSnapshotLabel() {
        val reference = ZonedDateTime.parse("2026-11-23T07:00Z[Europe/London]")
        val run = snoozed().copy(snoozeAt = reference.plusMinutes(5).toInstant().toEpochMilli())
        val next =
            AlarmOverview.nextScheduled(
                emptyList(),
                reference.toInstant().toEpochMilli(),
                listOf(run),
            )!!
        assertEquals(
            "Snoozed · Today at 07:05 · Work",
            AlarmOverview.scheduledDescription(next, reference, true, Locale.UK),
        )
        assertTrue(
            AlarmOverview.occurrenceStatus(run, ZoneId.of("Europe/London")).contains("07:05")
        )
    }

    @Test
    fun aMissedUndeliveredAlarmIsNotLockedForever() {
        val missed = alarm.copy(scheduledAtMillis = now - 300_001)
        assertNull(reason(missed))
        assertNotNull(reason(missed, listOf(snoozed())))
    }
}
