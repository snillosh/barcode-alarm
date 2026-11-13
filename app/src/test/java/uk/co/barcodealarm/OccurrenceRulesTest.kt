package uk.co.barcodealarm

import org.junit.Assert.*
import org.junit.Test

class OccurrenceRulesTest {
    private val normal = Alarm(1, 7, 0, "", "", isRinging = true)
    private val first = normal.copy(barcodeId = 1, barcodeValue = "A", barcodeFormat = "QR_CODE")
    private val two =
        first.copy(secondBarcodeId = 2, secondBarcodeValue = "B", secondBarcodeFormat = "QR_CODE")

    private fun occurrence(a: Alarm = two) = Occurrence(100, 1, a)

    private fun firstScan(o: Occurrence = occurrence()) =
        OccurrenceRules.scan(o, "A", "QR_CODE", 1000, 500)

    private fun verify() =
        OccurrenceRules.dismiss(occurrence(first.copy(verificationEnabled = true)), 1000)

    @Test
    fun noBarcodeCanSaveAndDismiss() {
        assertEquals(
            "",
            BarcodeLibrary.select(first.copy(isRinging = false), 0, emptyList()).barcodeValue,
        )
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.normalDismiss(occurrence(normal), 1000).stage,
        )
    }

    @Test
    fun protectedCannotUseDismissButton() {
        assertEquals(occurrence(), OccurrenceRules.normalDismiss(occurrence(), 1000))
    }

    @Test
    fun clearingFirstClearsSecond() {
        val a = BarcodeLibrary.select(two.copy(isRinging = false), 0, emptyList())
        assertEquals(0, a.secondBarcodeId)
        assertEquals("", a.secondBarcodeValue)
        assertEquals(0, a.scanIntervalMinutes)
    }

    @Test
    fun legacyJsonRetainsFirst() {
        val a =
            AlarmJsonCodec.decode(
                    """[{"id":1,"hour":7,"minute":0,"code":"A","format":"QR_CODE","barcodeId":1}]"""
                )
                .single()
        assertEquals("A", a.barcodeValue)
        assertEquals(0, a.secondBarcodeId)
    }

    @Test
    fun wrongFirstDoesNothing() {
        assertEquals(occurrence(), OccurrenceRules.scan(occurrence(), "X", "QR_CODE", 1000, 500))
    }

    @Test
    fun firstSilencesWithoutCompleting() {
        val o = firstScan()
        assertFalse(o.audible)
        assertTrue(o.active)
        assertEquals(OccurrenceStage.SECOND_WAIT, o.stage)
        assertEquals(1000L, o.firstScanAt)
    }

    @Test
    fun eligibilityAndTimeoutUseCorrectBases() {
        val o = firstScan()
        assertEquals(61000L, o.eligibleAt)
        assertEquals(361000L, o.deadlineAt)
        assertEquals(mapOf("ready" to 61000L, "timeout" to 361000L), OccurrenceRules.deadlines(o))
    }

    @Test
    fun tooEarlySecondDoesNothing() {
        val o = firstScan()
        assertEquals(o, OccurrenceRules.scan(o, "B", "QR_CODE", 61000, 60499))
    }

    @Test
    fun firstDoesNotCountAsSecond() {
        val o = firstScan()
        assertEquals(o, OccurrenceRules.scan(o, "A", "QR_CODE", 62000, 62000))
    }

    @Test
    fun eligibleSecondCompletes() {
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(firstScan(), "B", "QR_CODE", 62000, 62000).stage,
        )
    }

    @Test
    fun zeroIntervalAllowsSequentialScans() {
        val o = firstScan(occurrence(two.copy(scanIntervalMinutes = 0)))
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(o, "B", "QR_CODE", 1000, 500).stage,
        )
    }

    @Test
    fun readyDoesNotRingOrDismiss() {
        val o = OccurrenceRules.event(firstScan(), "ready", 61000)
        assertFalse(o.audible)
        assertEquals(OccurrenceStage.SECOND_READY, o.stage)
    }

    @Test
    fun timeoutEscalatesOnceAtFullVolume() {
        val o = OccurrenceRules.event(firstScan(), "timeout", 361000)
        assertTrue(o.audible)
        assertTrue(o.fullVolume)
        assertEquals(OccurrenceStage.SECOND_RINGING, o.stage)
        assertEquals(o, OccurrenceRules.event(o, "timeout", 400000))
        assertTrue(OccurrenceRules.deadlines(o).isEmpty())
    }

    @Test
    fun timeoutKeepsSecondStage() {
        val o = OccurrenceRules.event(firstScan(), "timeout", 361000)
        assertEquals(1000L, o.firstScanAt)
        assertEquals(o, OccurrenceRules.scan(o, "A", "QR_CODE", 400000, 400000))
        assertEquals(
            OccurrenceStage.COMPLETED,
            OccurrenceRules.scan(o, "B", "QR_CODE", 400000, 400000).stage,
        )
    }

    @Test
    fun mathsCompletesEitherStageAndCancelsDeadlines() {
        for (o in
            listOf(
                occurrence(),
                firstScan(),
                OccurrenceRules.event(firstScan(), "timeout", 361000),
            )) assertTrue(OccurrenceRules.deadlines(OccurrenceRules.dismiss(o, 400000)).isEmpty())
    }

    @Test
    fun duplicateFirstScanDoesNotMoveDeadline() {
        val o = firstScan()
        assertEquals(o, OccurrenceRules.scan(o, "A", "QR_CODE", 2000, 1500))
    }

    @Test
    fun verificationOffByDefault() {
        assertFalse(normal.verificationEnabled)
        assertEquals(OccurrenceStage.COMPLETED, OccurrenceRules.dismiss(occurrence(), 1000).stage)
    }

    @Test
    fun firstScanDoesNotStartVerification() {
        assertEquals(
            OccurrenceStage.SECOND_WAIT,
            firstScan(occurrence(two.copy(verificationEnabled = true))).stage,
        )
    }

    @Test
    fun verificationStartsAfterFullDismissalWithIndependentDeadline() {
        val o = verify()
        assertEquals(301000L, o.eligibleAt)
        assertEquals(421000L, o.deadlineAt)
        assertEquals(mapOf("verify" to 301000L, "failed" to 421000L), OccurrenceRules.deadlines(o))
    }

    @Test
    fun confirmationBeforeDueCannotBypassVerification() {
        val o = verify()
        assertEquals(o, OccurrenceRules.event(o, "awake", 300000))
    }

    @Test
    fun notificationTransitionsToResponseOnly() {
        assertEquals(
            OccurrenceStage.VERIFY_RESPONSE,
            OccurrenceRules.event(verify(), "verify", 301000).stage,
        )
    }

    @Test
    fun confirmationCancelsTimeoutAndDuplicatesAreHarmless() {
        val o = OccurrenceRules.event(verify(), "awake", 310000)
        assertEquals(OccurrenceStage.COMPLETED, o.stage)
        assertTrue(OccurrenceRules.deadlines(o).isEmpty())
        assertEquals(o, OccurrenceRules.event(o, "failed", 500000))
        assertEquals(o, OccurrenceRules.event(o, "awake", 320000))
    }

    @Test
    fun deadlineDoesNotDependOnNotificationDelivery() {
        val o = OccurrenceRules.event(verify(), "failed", 421000)
        assertEquals(OccurrenceStage.VERIFY_RINGING, o.stage)
        assertTrue(o.audible)
        assertTrue(o.fullVolume)
    }

    @Test
    fun lateConfirmationDoesNotSilenceEscalation() {
        val o = OccurrenceRules.event(verify(), "failed", 421000)
        assertEquals(o, OccurrenceRules.event(o, "awake", 422000))
    }

    @Test
    fun followupUsesOriginalSnapshotAndCannotVerifyAgain() {
        val o = OccurrenceRules.event(verify(), "failed", 421000)
        assertEquals("A", o.snapshot.barcodeValue)
        assertEquals(OccurrenceStage.COMPLETED, OccurrenceRules.dismiss(o, 500000).stage)
    }

    @Test
    fun twoStageFollowupRequiresBothAgain() {
        var o = OccurrenceRules.dismiss(occurrence(two.copy(verificationEnabled = true)), 1000)
        o = OccurrenceRules.event(o, "failed", 421000)
        assertEquals(o, OccurrenceRules.scan(o, "B", "QR_CODE", 422000, 422000))
        assertEquals(
            OccurrenceStage.SECOND_WAIT,
            OccurrenceRules.scan(o, "A", "QR_CODE", 422000, 422000).stage,
        )
    }

    @Test
    fun snoozeDoesNotStartVerification() {
        val o =
            OccurrenceRules.event(occurrence(two.copy(verificationEnabled = true)), "snooze", 1000)
        assertEquals(OccurrenceStage.SNOOZED, o.stage)
        assertEquals(mapOf("resume" to 301000L), OccurrenceRules.deadlines(o))
    }

    @Test
    fun secondEscalationSnoozePreservesOriginalEligibility() {
        val ringing = OccurrenceRules.event(firstScan(), "timeout", 361000)
        val snooze = OccurrenceRules.event(ringing, "snooze", 362000)
        val resumed = OccurrenceRules.event(snooze, "resume", 662000)
        assertEquals(OccurrenceStage.SECOND_RINGING, resumed.stage)
        assertEquals(61000L, resumed.eligibleAt)
        assertEquals(1000L, resumed.firstScanAt)
        assertTrue(resumed.fullVolume)
    }

    @Test
    fun verificationSnoozeDoesNotEnableAnotherVerification() {
        val failed = OccurrenceRules.event(verify(), "failed", 421000)
        val resumed =
            OccurrenceRules.event(OccurrenceRules.event(failed, "snooze", 422000), "resume", 722000)
        assertTrue(resumed.verificationConsumed)
        assertEquals(OccurrenceStage.COMPLETED, OccurrenceRules.dismiss(resumed, 800000).stage)
    }

    @Test
    fun editingDefinitionCannotChangeSnapshot() {
        val a = two
        val o = occurrence(a)
        val edited = a.copy(barcodeValue = "NEW")
        assertEquals("A", o.snapshot.barcodeValue)
        assertNotEquals(edited, o.snapshot)
    }

    @Test
    fun occurrencesHaveIndependentIdentityAndState() {
        val old = verify()
        val next = occurrence().copy(id = 101)
        assertEquals(OccurrenceStage.INITIAL, next.stage)
        assertEquals(OccurrenceStage.VERIFY_DELAY, old.stage)
        assertNotEquals(old.id, next.id)
    }

    @Test
    fun normalRingingDoesNotForceFullVolume() {
        assertFalse(occurrence().fullVolume)
    }

    @Test
    fun sameBarcodeCannotBeUsedForBothStages() {
        val a = first.copy(isRinging = false)
        assertThrows(IllegalArgumentException::class.java) {
            BarcodeLibrary.selectSecond(a, 1, listOf(SavedBarcode(1, "First", "A", "QR_CODE")))
        }
    }

    @Test
    fun equivalentRetailCodesCannotBeUsedForBothStages() {
        val a =
            first.copy(isRinging = false, barcodeValue = "0123456789012", barcodeFormat = "EAN_13")
        assertThrows(IllegalArgumentException::class.java) {
            BarcodeLibrary.selectSecond(
                a,
                2,
                listOf(SavedBarcode(2, "Second", "123456789012", "UPC_A")),
            )
        }
    }

    @Test
    fun snapshotFieldsSurviveJsonRecovery() {
        val a =
            two.copy(
                verificationEnabled = true,
                verificationDelayMinutes = 7,
                verificationResponseMinutes = 3,
                secondBarcodeName = "Coffee jar",
            )
        assertEquals(a, AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(a))).single())
    }

    @Test
    fun disablingAndDeletingCanCancelEveryPendingState() {
        for (o in
            listOf(firstScan(), verify(), OccurrenceRules.event(occurrence(), "snooze", 1000))) {
            assertTrue(
                OccurrenceRules.deadlines(o.copy(stage = OccurrenceStage.COMPLETED)).isEmpty()
            )
            assertFalse(o.copy(stage = OccurrenceStage.COMPLETED).audible)
        }
    }

    @Test
    fun rebootRebasesMonotonicWaitWithoutChangingWallDeadlinesOrProgress() {
        val waiting =
            firstScan()
                .copy(
                    snapshot =
                        two.copy(
                            correctAnswerCount = 8,
                            mathsStartedAtElapsedMillis = 100,
                            playbackStartedAtElapsedMillis = 100,
                        )
                )
        val restored = OccurrenceRules.afterBoot(waiting, waiting.eligibleAt - 12_000, 400)
        assertEquals(12_400L, restored.eligibleElapsed)
        assertEquals(waiting.firstScanAt, restored.firstScanAt)
        assertEquals(waiting.stage, restored.stage)
        assertEquals(OccurrenceRules.deadlines(waiting), OccurrenceRules.deadlines(restored))
        assertEquals(0, restored.snapshot.correctAnswerCount)
        assertEquals(-1L, restored.snapshot.mathsStartedAtElapsedMillis)
    }

    @Test
    fun overdueRebootKeepsIndependentVerificationDeadline() {
        val pending = verify()
        val restored = OccurrenceRules.afterBoot(pending, pending.deadlineAt + 10, 400)
        assertEquals(OccurrenceRules.deadlines(pending), OccurrenceRules.deadlines(restored))
        assertEquals(
            OccurrenceStage.VERIFY_RINGING,
            OccurrenceRules.event(restored, "failed", pending.deadlineAt + 10).stage,
        )
    }

    @Test
    fun everyPendingStageSurvivesProcessRecreationWithItsOwnDeadlines() {
        for (stage in OccurrenceStage.values().filter { it != OccurrenceStage.COMPLETED }) {
            val saved =
                firstScan()
                    .copy(
                        stage = stage,
                        id = 123,
                        definitionId = 8,
                        verificationConsumed = true,
                        resumeStage = OccurrenceStage.SECOND_RINGING,
                        fullVolume = true,
                        snoozeAt = 123456,
                    )
            val recovered =
                OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(saved))).single()
            assertEquals(saved, recovered)
            assertEquals(OccurrenceRules.deadlines(saved), OccurrenceRules.deadlines(recovered))
        }
    }

    @Test
    fun completedRecordsAreRemovedWithoutAffectingAnotherOccurrence() {
        val pending = firstScan()
        val completed = pending.copy(id = 101, stage = OccurrenceStage.COMPLETED)
        assertEquals(
            listOf(pending),
            OccurrenceJsonCodec.decode(OccurrenceJsonCodec.encode(listOf(completed, pending))),
        )
    }
}
