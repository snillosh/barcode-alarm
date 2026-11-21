package uk.co.barcodealarm

import android.content.Context

/** Defaults apply to new runs; each occurrence retains its protection through edits/reboots. */
class TimerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("timer_settings", Context.MODE_PRIVATE)

    fun cancellationLockMinutes(): Int =
        preferences.getInt("cancellation_lock_minutes", 1).coerceIn(0, 60)

    fun saveCancellationLockMinutes(minutes: Int) {
        require(minutes in 0..60) { "Timer cancellation protection must be 0–60 minutes" }
        check(preferences.edit().putInt("cancellation_lock_minutes", minutes).commit()) {
            "Could not save timer settings"
        }
    }
}
