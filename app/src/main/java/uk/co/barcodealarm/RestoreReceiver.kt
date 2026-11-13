package uk.co.barcodealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.time.ZonedDateTime

/** Restore pending deliveries; repeating alarms follow local weekdays after clock/zone changes. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        val scheduler = AlarmScheduler(context)
        val now = ZonedDateTime.now()
        val bootCount =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val isDeviceBoot =
            intent.action == Intent.ACTION_BOOT_COMPLETED &&
                AlarmRules.isNewDeviceBoot(store.getLastRestoredBootCount(), bootCount)
        val isClockChange =
            intent.action == Intent.ACTION_TIME_CHANGED ||
                intent.action == Intent.ACTION_TIMEZONE_CHANGED
        try {
            OccurrenceManager(context).migrateLegacy()
        } catch (exception: Exception) {
            store.log("Active alarm migration failed: $exception")
            return
        }
        for (alarm in store.getAlarms()) {
            val restored =
                AlarmRules.restoreAfterSystemChange(
                    alarm,
                    now,
                    isClockChange = isClockChange,
                    isDeviceBoot = isDeviceBoot,
                )
            if (restored != alarm) {
                store.saveAlarm(restored)
                store.log(
                    "Restored ${alarm.id}: enabled=${restored.isEnabled}, due=${restored.scheduledAtMillis}"
                )
            }
            if (!restored.isEnabled || restored.isRinging) continue
            try {
                scheduler.schedule(restored)
            } catch (exception: Exception) {
                store.saveAlarm(AlarmRules.markDismissed(restored))
                store.log("Restore failed: $exception")
            }
        }
        try {
            OccurrenceManager(context).restore(isDeviceBoot)
        } catch (exception: Exception) {
            // Retain state and the previous boot marker so opening the app can retry and
            // display the scheduling/permission diagnostic.
            store.log("Occurrence restore failed: $exception")
            return
        }
        // Record only after restoration completed, so an interrupted restoration can retry.
        if (isDeviceBoot && bootCount >= 0) store.recordRestoredBoot(bootCount)
        // No boot-time media service: an unresolved ringing alarm resumes on app open.
    }
}
