package uk.co.barcodealarm

import java.time.ZonedDateTime

/** Pure business rules: no Android APIs, storage, or screen state. `object` is a singleton. */
object AlarmRules {
    const val MATHS_DURATION_MILLIS = 300_000L
    const val REQUIRED_CORRECT_ANSWERS = 15

    fun calculateNextOccurrence(hour: Int, minute: Int, now: ZonedDateTime): Long {
        // atZone handles DST gaps by moving forward, and overlaps using the earlier offset.
        var occurrence = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        if (!occurrence.isAfter(now)) {
            occurrence = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
        }
        return occurrence.toInstant().toEpochMilli()
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
        // Preserve the alarm definition and barcode; never schedule another occurrence here.
        return alarm.copy(
            isEnabled = false,
            scheduledAtMillis = 0,
            isRinging = false,
            mathsStartedAtElapsedMillis = -1,
            correctAnswerCount = 0,
        )
    }
}
