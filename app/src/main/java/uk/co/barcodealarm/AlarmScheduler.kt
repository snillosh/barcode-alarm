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
            AlarmRules.calculateNextOccurrence(alarm.hour, alarm.minute, ZonedDateTime.now()),
    ) {
        check(!alarm.isRinging) { "Alarm is ringing" }
        cancel(alarm)

        val enabledAlarm =
            alarm.copy(
                isEnabled = true,
                scheduledAtMillis = scheduledAtMillis,
                mathsStartedAtElapsedMillis = -1,
                correctAnswerCount = 0,
            )
        store.saveAlarm(enabledAlarm)
        try {
            schedule(enabledAlarm)
        } catch (exception: Exception) {
            // Never leave the UI claiming an alarm is enabled when Android rejected it.
            store.saveAlarm(enabledAlarm.copy(isEnabled = false, scheduledAtMillis = 0))
            store.log("Schedule failed: $exception")
            throw exception
        }
    }

    fun disable(alarm: Alarm) {
        check(!alarm.isRinging)
        cancel(alarm)
        store.saveAlarm(AlarmRules.markDismissed(alarm))
    }
}
