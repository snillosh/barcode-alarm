package uk.co.barcodealarm

/** Reusable configuration. The Alarm record supplies the existing sound/barcode options only. */
data class TimerPreset(
    val id: Int,
    val durationMillis: Long,
    val configuration: Alarm = Alarm(id, 0, 0, "", ""),
    val reminderMinutes: Int = 0,
)

object TimerRules {
    const val MAX_DURATION_MILLIS = 7 * 24 * 60 * 60_000L

    fun validate(preset: TimerPreset): TimerPreset {
        require(preset.durationMillis in 1000..MAX_DURATION_MILLIS) {
            "Choose a duration between 1 second and 7 days"
        }
        require(preset.reminderMinutes in 0..10080) {
            "Reminder interval must be 1–10080 minutes, or off"
        }
        require(preset.configuration.scanIntervalMinutes in 0..30) {
            "Scan interval must be 0–30 minutes"
        }
        val config = preset.configuration
        require(
            config.secondBarcodeValue.isEmpty() ||
                (config.barcodeValue.isNotEmpty() &&
                    !AlarmRules.barcodesMatch(
                        config.barcodeValue,
                        config.barcodeFormat,
                        config.secondBarcodeValue,
                        config.secondBarcodeFormat,
                    ))
        ) {
            "Choose different barcodes for each stage"
        }
        return preset.copy(
            configuration =
                AlarmRules.markDismissed(config)
                    .copy(
                        id = preset.id,
                        label = AlarmOverview.cleanLabel(config.label),
                        repeatDays = emptySet(),
                        verificationEnabled = false,
                    )
        )
    }

    fun savePreset(presets: List<TimerPreset>, preset: TimerPreset): List<TimerPreset> {
        val clean = validate(preset)
        return presets.filterNot { it.id == clean.id } + clean
    }

    fun deletePreset(presets: List<TimerPreset>, id: Int): List<TimerPreset> =
        presets.filterNot { it.id == id }

    fun label(preset: TimerPreset): String =
        AlarmOverview.cleanLabel(preset.configuration.label).ifEmpty { "Timer" }

    fun start(preset: TimerPreset, id: Int, now: Long, elapsed: Long): Occurrence {
        val clean = validate(preset)
        return Occurrence(
            id,
            clean.id,
            clean.configuration.copy(
                id = id,
                isRinging = true,
                label = label(clean),
                verificationEnabled = false,
            ),
            stage = OccurrenceStage.COUNTDOWN,
            source = OccurrenceSource.TIMER,
            timerStartedAt = now,
            timerStartedElapsed = elapsed,
            timerExpiresAt = now + clean.durationMillis,
            timerExpiresElapsed = elapsed + clean.durationMillis,
            timerDurationMillis = clean.durationMillis,
            reminderMinutes = clean.reminderMinutes,
        )
    }

    fun remaining(occurrence: Occurrence, now: Long, elapsed: Long): Long =
        (if (occurrence.timerExpiresElapsed > 0) occurrence.timerExpiresElapsed - elapsed
            else occurrence.timerExpiresAt - now)
            .coerceAtLeast(0)

    fun cancel(occurrence: Occurrence, now: Long, elapsed: Long): Occurrence =
        if (
            occurrence.source == OccurrenceSource.TIMER &&
                occurrence.stage == OccurrenceStage.COUNTDOWN &&
                remaining(occurrence, now, elapsed) > 0
        )
            occurrence.copy(stage = OccurrenceStage.CANCELLED)
        else occurrence

    fun nextReminderOffset(occurrence: Occurrence): Long? {
        if (occurrence.stage != OccurrenceStage.COUNTDOWN || occurrence.reminderMinutes <= 0)
            return null
        val offset = (occurrence.reminderIndex + 1L) * occurrence.reminderMinutes * 60_000
        return offset.takeIf { it < occurrence.timerDurationMillis }
    }

    fun deadlines(occurrence: Occurrence): Map<String, Long> {
        if (occurrence.stage != OccurrenceStage.COUNTDOWN) return emptyMap()
        val events = mutableMapOf("expire" to occurrence.timerExpiresAt)
        nextReminderOffset(occurrence)?.let {
            events["reminder"] = occurrence.timerExpiresAt - occurrence.timerDurationMillis + it
        }
        return events
    }

    fun event(occurrence: Occurrence, event: String, now: Long, elapsed: Long): Occurrence {
        if (
            occurrence.source != OccurrenceSource.TIMER ||
                occurrence.stage != OccurrenceStage.COUNTDOWN
        )
            return occurrence
        val remaining = remaining(occurrence, now, elapsed)
        if (event == "expire" && remaining == 0L)
            return occurrence.copy(stage = OccurrenceStage.INITIAL)
        val offset = nextReminderOffset(occurrence) ?: return occurrence
        val passed = occurrence.timerDurationMillis - remaining
        if (event != "reminder" || remaining == 0L || passed < offset) return occurrence
        // A delayed callback produces at most one chime; never replay a backlog of reminders.
        return occurrence.copy(
            reminderIndex = (passed / (occurrence.reminderMinutes * 60_000L)).toInt()
        )
    }

    fun restore(occurrence: Occurrence, now: Long, elapsed: Long, isBoot: Boolean): Occurrence {
        if (occurrence.stage != OccurrenceStage.COUNTDOWN) return occurrence
        val remaining =
            if (isBoot) (occurrence.timerExpiresAt - now).coerceAtLeast(0)
            else remaining(occurrence, now, elapsed)
        val passed = (occurrence.timerDurationMillis - remaining).coerceAtLeast(0)
        return occurrence.copy(
            timerExpiresAt = now + remaining,
            timerExpiresElapsed = elapsed + remaining,
            timerStartedElapsed = elapsed + remaining - occurrence.timerDurationMillis,
            reminderIndex =
                if (occurrence.reminderMinutes > 0)
                    maxOf(
                        occurrence.reminderIndex,
                        (passed / (occurrence.reminderMinutes * 60_000L)).toInt(),
                    )
                else 0,
        )
    }

    fun countdown(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) + 999) / 1000
        val hours = seconds / 3600
        return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
