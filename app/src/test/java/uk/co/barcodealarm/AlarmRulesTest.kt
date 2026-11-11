package uk.co.barcodealarm

import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary tests keep alarm timing and dismissal behaviour stable during refactoring. */
class AlarmRulesTest {
    // The challenge starts at 1,000 ms, so the five-minute boundary is 301,000 ms.
    private val ringingAlarm =
        Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            barcodeValue = "123",
            barcodeFormat = "QR_CODE",
            isEnabled = true,
            scheduledAtMillis = 42,
            isRinging = true,
            mathsStartedAtElapsedMillis = 1000,
            correctAnswerCount = 15,
        )

    private fun nextOccurrenceAsUtc(now: String): String {
        val localTime = ZonedDateTime.parse(now)
        val scheduledAtMillis = AlarmRules.calculateNextOccurrence(7, 0, localTime)
        return Instant.ofEpochMilli(scheduledAtMillis).toString()
    }

    @Test
    fun consumesDuplicateDelivery() {
        val scheduled = ringingAlarm.copy(isRinging = false)
        assertTrue(AlarmRules.shouldTrigger(scheduled, 42))
        assertFalse(
            AlarmRules.shouldTrigger(
                scheduled.copy(isEnabled = false, scheduledAtMillis = 0, isRinging = true),
                42,
            )
        )
        assertFalse(AlarmRules.shouldTrigger(scheduled, 41))
        assertFalse(AlarmRules.shouldTrigger(AlarmRules.markDismissed(scheduled), 42))
    }

    @Test
    fun laterToday() {
        assertEquals(
            "2026-11-11T07:00:00Z",
            nextOccurrenceAsUtc("2026-11-11T06:00:00Z[Europe/London]"),
        )
    }

    @Test
    fun tomorrow() {
        assertEquals(
            "2026-11-12T07:00:00Z",
            nextOccurrenceAsUtc("2026-11-11T22:00:00Z[Europe/London]"),
        )
    }

    @Test
    fun exactMinuteIsTomorrow() {
        assertEquals(
            "2026-11-12T07:00:00Z",
            nextOccurrenceAsUtc("2026-11-11T07:00:00Z[Europe/London]"),
        )
    }

    @Test
    fun dstGapMovesForward() {
        assertEquals(
            Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(),
            AlarmRules.calculateNextOccurrence(
                1,
                30,
                ZonedDateTime.parse("2026-03-29T00:00:00Z[Europe/London]"),
            ),
        )
    }

    @Test
    fun dismissalDisablesAndConsumes() {
        val dismissedAlarm = AlarmRules.markDismissed(ringingAlarm)
        assertFalse(dismissedAlarm.isEnabled)
        assertFalse(dismissedAlarm.isRinging)
        assertEquals(0L, dismissedAlarm.scheduledAtMillis)
        assertEquals(ringingAlarm.barcodeValue, dismissedAlarm.barcodeValue)
    }

    @Test
    fun identicalMatches() {
        assertTrue(AlarmRules.barcodesMatch("abc", "QR_CODE", "abc", "QR_CODE"))
    }

    @Test
    fun wrongCodeRejected() {
        assertFalse(AlarmRules.barcodesMatch("abc", "QR_CODE", "abd", "QR_CODE"))
    }

    @Test
    fun differentFormatRejected() {
        assertFalse(AlarmRules.barcodesMatch("123", "QR_CODE", "123", "CODE_128"))
    }

    @Test
    fun equivalentUpc() {
        assertTrue(AlarmRules.barcodesMatch("0123456789012", "EAN_13", "123456789012", "UPC_A"))
    }

    @Test
    fun timeAloneInsufficient() {
        assertFalse(
            AlarmRules.canDismissWithMaths(ringingAlarm.copy(correctAnswerCount = 14), 301000)
        )
    }

    @Test
    fun answersAloneInsufficient() {
        assertFalse(AlarmRules.canDismissWithMaths(ringingAlarm, 300999))
    }

    @Test
    fun bothRequirements() {
        assertTrue(AlarmRules.canDismissWithMaths(ringingAlarm, 301000))
    }

    @Test
    fun noStartCannotDismiss() {
        assertFalse(
            AlarmRules.canDismissWithMaths(
                ringingAlarm.copy(mathsStartedAtElapsedMillis = -1),
                900000,
            )
        )
    }

    @Test
    fun monotonicRollbackCannotDismiss() {
        assertFalse(AlarmRules.canDismissWithMaths(ringingAlarm, 0))
    }

    @Test
    fun questionsHaveFourUniqueAnswers() {
        repeat(1000) {
            val question = MathsQuestion.create()
            assertEquals(4, question.choices.toSet().size)
            assertTrue(question.answer in question.choices)
        }
    }
}
