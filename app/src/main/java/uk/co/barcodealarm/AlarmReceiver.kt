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

        // Consume this delivery before playback. A repeat series stays enabled, while
        // isRinging rejects duplicate deliveries until dismissal schedules its next day.
        store.saveAlarm(AlarmRules.markTriggered(alarm))
        store.log("Triggered ${alarm.id}")
        try {
            context.startForegroundService(Intent(context, RingService::class.java))
        } catch (exception: Exception) {
            store.log("Service start failed: $exception")
        }
    }
}
