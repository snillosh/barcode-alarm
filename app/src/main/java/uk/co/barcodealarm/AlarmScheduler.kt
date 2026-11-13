package uk.co.barcodealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

/** Android scheduling adapter. AlarmRules calculates times; AlarmManager delivers them. */
class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val store = AlarmStore(context)

    private fun createAlarmPendingIntent(alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        intent.putExtra("id", alarm.id)
        intent.putExtra("due", alarm.scheduledAtMillis)

        // Android retains this token even after our process dies. The alarm ID identifies it;
        // FLAG_UPDATE_CURRENT replaces its timestamp instead of creating duplicate occurrences.
        return PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancel(alarm: Alarm) {
        alarmManager.cancel(createAlarmPendingIntent(alarm))
    }

    fun schedule(alarm: Alarm) {
        val hasExactAlarmAccess =
            Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        check(hasExactAlarmAccess) { "Allow exact alarms in Settings first" }

        val openApp =
            PendingIntent.getActivity(
                context,
                alarm.id,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val alarmClockInfo = AlarmManager.AlarmClockInfo(alarm.scheduledAtMillis, openApp)
        alarmManager.setAlarmClock(alarmClockInfo, createAlarmPendingIntent(alarm))
        store.log("Scheduled ${alarm.id} at ${alarm.scheduledAtMillis}")
    }

    fun enable(
        alarm: Alarm,
        scheduledAtMillis: Long =
            AlarmRules.calculateNextOccurrence(
                alarm.hour,
                alarm.minute,
                ZonedDateTime.now(),
                alarm.repeatDays,
            ),
    ): Alarm {
        check(!alarm.isRinging) { "Alarm is ringing" }
        cancel(alarm)

        val enabledAlarm = AlarmRules.prepareEnabled(alarm, scheduledAtMillis)
        store.saveAlarm(enabledAlarm)
        try {
            schedule(enabledAlarm)
        } catch (exception: Exception) {
            // Never leave the UI claiming an alarm is enabled when Android rejected it.
            store.saveAlarm(AlarmRules.markDismissed(enabledAlarm))
            store.log("Schedule failed: $exception")
            throw exception
        }
        return enabledAlarm
    }

    fun snooze(alarm: Alarm) {
        val snoozed = AlarmRules.snooze(alarm, System.currentTimeMillis())
        store.saveAlarm(snoozed)
        try {
            schedule(snoozed)
            store.log("Snoozed ${alarm.id} until ${snoozed.scheduledAtMillis}")
        } catch (exception: Exception) {
            // Do not silence playback unless Android actually accepted the snooze.
            store.saveAlarm(alarm)
            store.log("Snooze failed: $exception")
            throw exception
        }
    }

    /** Returns false when dismissal succeeded but the next repeat could not be registered. */
    fun dismiss(alarm: Alarm): Boolean {
        val nextOccurrence = AlarmRules.completeOccurrence(alarm, ZonedDateTime.now())
        store.saveAlarm(AlarmRules.markDismissed(alarm))
        if (!nextOccurrence.isEnabled) return true
        try {
            // enable() persists first and rolls back the enabled flag on scheduling failure.
            enable(nextOccurrence, nextOccurrence.scheduledAtMillis)
            return true
        } catch (exception: Exception) {
            store.log("Repeat reschedule failed for ${alarm.id}: $exception")
            return false
        }
    }

    fun disable(alarm: Alarm) {
        OccurrenceManager(context).cancelDefinition(alarm.id)
        check(!alarm.isRinging)
        cancel(alarm)
        store.saveAlarm(AlarmRules.markDismissed(alarm))
    }
}
