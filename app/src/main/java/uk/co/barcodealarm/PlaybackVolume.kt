package uk.co.barcodealarm

/** App-player gain only: these settings never change Android's system alarm-volume stream. */
data class PlaybackSettings(
    val gradualVolumeEnabled: Boolean = false,
    val rampDurationSeconds: Int = 60,
) {
    companion object {
        val SUPPORTED_DURATIONS_SECONDS = listOf(30, 60, 120, 300)
    }
}

/** Pure, monotonic-time volume calculation; kept separate from MediaPlayer for unit testing. */
object PlaybackVolume {
    private const val STARTING_GAIN = 0.2f
    private const val MATHS_GAIN_MULTIPLIER = 0.2f

    fun calculateGain(
        settings: PlaybackSettings,
        startedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
        isMathsVisible: Boolean,
    ): Float {
        var gain = 1f
        if (settings.gradualVolumeEnabled) {
            val durationSeconds =
                settings.rampDurationSeconds.takeIf { seconds ->
                    seconds in PlaybackSettings.SUPPORTED_DURATIONS_SECONDS
                } ?: 60
            val elapsedMillis =
                if (startedAtElapsedMillis >= 0) {
                    (nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0)
                } else 0
            val progress =
                (elapsedMillis.toDouble() / (durationSeconds * 1_000L)).coerceIn(0.0, 1.0)
            gain = STARTING_GAIN + (1f - STARTING_GAIN) * progress.toFloat()
        }
        // Maths remains quieter relative to the alarm's current ramp level.
        return if (isMathsVisible) gain * MATHS_GAIN_MULTIPLIER else gain
    }
}
