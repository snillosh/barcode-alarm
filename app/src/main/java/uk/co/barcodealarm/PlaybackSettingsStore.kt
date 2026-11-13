package uk.co.barcodealarm

import android.content.Context

/** Separate preference file: changing audio appearance does not rewrite saved alarms. */
class PlaybackSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun read(): PlaybackSettings {
        val savedDuration = preferences.getInt("ramp_duration_seconds", 60)
        val duration =
            savedDuration.takeIf { seconds ->
                seconds in PlaybackSettings.SUPPORTED_DURATIONS_SECONDS
            } ?: 60
        return PlaybackSettings(
            gradualVolumeEnabled = preferences.getBoolean("gradual_volume_enabled", false),
            rampDurationSeconds = duration,
        )
    }

    fun save(settings: PlaybackSettings) {
        require(settings.rampDurationSeconds in PlaybackSettings.SUPPORTED_DURATIONS_SECONDS)
        val saved =
            preferences
                .edit()
                .putBoolean("gradual_volume_enabled", settings.gradualVolumeEnabled)
                .putInt("ramp_duration_seconds", settings.rampDurationSeconds)
                .commit()
        check(saved) { "Could not save playback settings" }
    }
}
