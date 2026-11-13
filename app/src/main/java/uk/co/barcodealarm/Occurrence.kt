package uk.co.barcodealarm

/** Durable state, independent of an Activity and of subsequent edits to the definition. */
enum class OccurrenceStage {
    INITIAL,
    SECOND_WAIT,
    SECOND_READY,
    SECOND_RINGING,
    VERIFY_DELAY,
    VERIFY_RESPONSE,
    VERIFY_RINGING,
    SNOOZED,
    COMPLETED,
}

/** Durable deadlines are Unix milliseconds; eligibleElapsed is monotonic time within one boot. */
data class Occurrence(
    val id: Int,
    val definitionId: Int,
    val snapshot: Alarm,
    val stage: OccurrenceStage = OccurrenceStage.INITIAL,
    val eligibleAt: Long = 0,
    val deadlineAt: Long = 0,
    val eligibleElapsed: Long = 0,
    val firstScanAt: Long = 0,
    val verificationConsumed: Boolean = false,
    val resumeStage: OccurrenceStage = OccurrenceStage.INITIAL,
    val fullVolume: Boolean = false,
    val snoozeAt: Long = 0,
) {
    val audible
        get() =
            stage in
                setOf(
                    OccurrenceStage.INITIAL,
                    OccurrenceStage.SECOND_RINGING,
                    OccurrenceStage.VERIFY_RINGING,
                )

    val active
        get() =
            audible || stage == OccurrenceStage.SECOND_WAIT || stage == OccurrenceStage.SECOND_READY
}

/** All times are supplied by callers, making transitions deterministic in JVM tests. */
object OccurrenceRules {
    fun scan(
        occurrence: Occurrence,
        value: String,
        format: String,
        now: Long,
        elapsed: Long,
    ): Occurrence {
        val alarm = occurrence.snapshot
        if (!occurrence.active) return occurrence
        val second =
            occurrence.stage in
                setOf(
                    OccurrenceStage.SECOND_WAIT,
                    OccurrenceStage.SECOND_READY,
                    OccurrenceStage.SECOND_RINGING,
                )
        if (second) {
            if (
                now < occurrence.eligibleAt ||
                    (occurrence.eligibleElapsed > 0 && elapsed < occurrence.eligibleElapsed)
            )
                return occurrence
            return if (
                AlarmRules.barcodesMatch(
                    alarm.secondBarcodeValue,
                    alarm.secondBarcodeFormat,
                    value,
                    format,
                )
            )
                dismiss(occurrence, now)
            else occurrence
        }
        if (
            alarm.barcodeValue.isEmpty() ||
                !AlarmRules.barcodesMatch(alarm.barcodeValue, alarm.barcodeFormat, value, format)
        )
            return occurrence
        if (alarm.secondBarcodeValue.isEmpty()) return dismiss(occurrence, now)
        val delay = alarm.scanIntervalMinutes * 60_000L
        return occurrence.copy(
            stage = OccurrenceStage.SECOND_WAIT,
            firstScanAt = now,
            eligibleAt = now + delay,
            eligibleElapsed = elapsed + delay,
            deadlineAt = now + delay + 300_000,
        )
    }

    fun normalDismiss(occurrence: Occurrence, now: Long): Occurrence =
        if (occurrence.active && occurrence.snapshot.barcodeValue.isEmpty())
            dismiss(occurrence, now)
        else occurrence

    fun dismiss(occurrence: Occurrence, now: Long): Occurrence {
        if (!occurrence.active) return occurrence
        if (!occurrence.snapshot.verificationEnabled || occurrence.verificationConsumed)
            return occurrence.copy(stage = OccurrenceStage.COMPLETED)
        val due = now + occurrence.snapshot.verificationDelayMinutes * 60_000L
        return occurrence.copy(
            stage = OccurrenceStage.VERIFY_DELAY,
            eligibleAt = due,
            deadlineAt = due + occurrence.snapshot.verificationResponseMinutes * 60_000L,
            eligibleElapsed = 0,
        )
    }

    fun event(occurrence: Occurrence, event: String, now: Long): Occurrence =
        when {
            event == "ready" &&
                occurrence.stage == OccurrenceStage.SECOND_WAIT &&
                now >= occurrence.eligibleAt ->
                occurrence.copy(stage = OccurrenceStage.SECOND_READY)
            event == "timeout" &&
                occurrence.stage in
                    setOf(OccurrenceStage.SECOND_WAIT, OccurrenceStage.SECOND_READY) &&
                now >= occurrence.deadlineAt ->
                occurrence.copy(stage = OccurrenceStage.SECOND_RINGING, fullVolume = true)
            event == "verify" &&
                occurrence.stage == OccurrenceStage.VERIFY_DELAY &&
                now >= occurrence.eligibleAt ->
                occurrence.copy(stage = OccurrenceStage.VERIFY_RESPONSE)
            event == "failed" &&
                occurrence.stage in
                    setOf(OccurrenceStage.VERIFY_DELAY, OccurrenceStage.VERIFY_RESPONSE) &&
                now >= occurrence.deadlineAt ->
                occurrence.copy(
                    stage = OccurrenceStage.VERIFY_RINGING,
                    verificationConsumed = true,
                    fullVolume = true,
                    snapshot =
                        occurrence.snapshot.copy(
                            mathsStartedAtElapsedMillis = -1,
                            correctAnswerCount = 0,
                            playbackStartedAtElapsedMillis = -1,
                        ),
                )
            event == "awake" &&
                occurrence.stage in
                    setOf(OccurrenceStage.VERIFY_DELAY, OccurrenceStage.VERIFY_RESPONSE) &&
                now >= occurrence.eligibleAt &&
                now < occurrence.deadlineAt -> occurrence.copy(stage = OccurrenceStage.COMPLETED)
            event == "snooze" && occurrence.audible ->
                occurrence.copy(
                    stage = OccurrenceStage.SNOOZED,
                    resumeStage = occurrence.stage,
                    snoozeAt = now + AlarmRules.SNOOZE_DURATION_MILLIS,
                )
            event == "resume" &&
                occurrence.stage == OccurrenceStage.SNOOZED &&
                now >= occurrence.snoozeAt ->
                occurrence.copy(
                    stage = occurrence.resumeStage,
                    snapshot = occurrence.snapshot.copy(playbackStartedAtElapsedMillis = -1),
                )
            else -> occurrence
        }

    /** A reboot invalidates monotonic timestamps; wall deadlines remain the durable authority. */
    fun afterBoot(occurrence: Occurrence, now: Long, elapsed: Long): Occurrence =
        occurrence.copy(
            eligibleElapsed =
                if (occurrence.eligibleElapsed > 0)
                    elapsed + (occurrence.eligibleAt - now).coerceAtLeast(0)
                else 0,
            snapshot =
                occurrence.snapshot.copy(
                    mathsStartedAtElapsedMillis = -1,
                    correctAnswerCount = 0,
                    playbackStartedAtElapsedMillis = -1,
                ),
        )

    fun deadlines(occurrence: Occurrence): Map<String, Long> =
        when (occurrence.stage) {
            OccurrenceStage.SECOND_WAIT ->
                mapOf("ready" to occurrence.eligibleAt, "timeout" to occurrence.deadlineAt)
            OccurrenceStage.SECOND_READY -> mapOf("timeout" to occurrence.deadlineAt)
            OccurrenceStage.VERIFY_DELAY ->
                mapOf("verify" to occurrence.eligibleAt, "failed" to occurrence.deadlineAt)
            OccurrenceStage.VERIFY_RESPONSE -> mapOf("failed" to occurrence.deadlineAt)
            OccurrenceStage.SNOOZED -> mapOf("resume" to occurrence.snoozeAt)
            else -> emptyMap()
        }
}
