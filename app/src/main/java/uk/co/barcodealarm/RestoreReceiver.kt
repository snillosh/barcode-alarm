package uk.co.barcodealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores still-future occurrences after boot, clock changes, or a package update. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        val isDeviceBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val scheduler = AlarmScheduler(context)

        for (alarm in store.getAlarms()) {
            if (alarm.isRinging && isDeviceBoot) {
                // elapsedRealtime starts over at boot, so old maths timing is no longer valid.
                store.saveAlarm(
                    alarm.copy(mathsStartedAtElapsedMillis = -1, correctAnswerCount = 0)
                )
            }
            if (!alarm.isEnabled) {
                continue
            }
            if (alarm.scheduledAtMillis <= System.currentTimeMillis()) {
                store.saveAlarm(alarm.copy(isEnabled = false, scheduledAtMillis = 0))
                store.log("Missed ${alarm.id}; re-enable manually")
                continue
            }
            try {
                // Retain the saved timestamp; do not silently move the alarm to tomorrow.
                scheduler.schedule(alarm)
            } catch (exception: Exception) {
                store.saveAlarm(alarm.copy(isEnabled = false, scheduledAtMillis = 0))
                store.log("Restore failed: $exception")
            }
        }
        // Android restricts boot-time media services. Unresolved ringing resumes on app open.
    }
}
