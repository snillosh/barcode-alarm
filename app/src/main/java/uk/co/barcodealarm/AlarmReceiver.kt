package uk.co.barcodealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android calls this short-lived entry point when a scheduled alarm fires. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        val alarmId = intent.getIntExtra("id", -1)
        // ?: return is a null guard, equivalent to `if (alarm == null) return` in C#.
        val alarm = store.getAlarm(alarmId) ?: return
        val deliveredTime = intent.getLongExtra("due", -1)
        if (!AlarmRules.shouldTrigger(alarm, deliveredTime)) {
            return
        }

        // Consume this delivery before playback. Scheduling the next repeat now lets it
        // coexist with this occurrence, including any pending verification.
        OccurrenceStore(context).create(alarm)
        store.saveAlarm(AlarmRules.markDismissed(alarm))
        if (alarm.repeatDays.isNotEmpty()) {
            try {
                AlarmScheduler(context).enable(alarm.copy(isRinging = false))
            } catch (e: Exception) {
                store.log("Next repeat failed: $e")
            }
        }
        store.log("Triggered ${alarm.id}")
        try {
            context.startForegroundService(Intent(context, RingService::class.java))
        } catch (exception: Exception) {
            store.log("Service start failed: $exception")
        }
    }
}
