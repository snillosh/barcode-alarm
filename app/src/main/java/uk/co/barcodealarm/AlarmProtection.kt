package uk.co.barcodealarm

import android.content.Context

/** Calendar-alarm protection is separate from countdown-timer protection. */
class AlarmSettings(context: Context) {
    private val preferences = context.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)

    fun protectionMinutes(): Int = preferences.getInt("protection_minutes", 5).coerceIn(1, 60)

    fun saveProtectionMinutes(minutes: Int) {
        require(minutes in 1..60) { "Alarm protection must be 1–60 minutes" }
        check(preferences.edit().putInt("protection_minutes", minutes).commit()) {
            "Could not save alarm settings"
        }
    }
}

object AlarmProtectionRules {
    fun reason(
        alarm: Alarm?,
        occurrences: List<Occurrence>,
        definitionId: Int,
        now: Long,
    ): String? {
        if (
            occurrences.any {
                it.belongsTo(OccurrenceSource.ALARM, definitionId) && !it.terminal
            } || alarm?.isRinging == true || alarm?.isSnoozed == true
        )
            return "This alarm is active or snoozed. Dismiss it normally before changing, disabling or deleting it."
        // Keep a grace period for a delayed delivery, but do not permanently lock a
        // missed definition that never produced an occurrence (for example after force-stop).
        if (
            alarm != null &&
                alarm.isEnabled &&
                alarm.scheduledAtMillis > 0 &&
                alarm.scheduledAtMillis - now in
                    -(alarm.alarmProtectionMinutes * 60_000L)..(alarm.alarmProtectionMinutes *
                            60_000L)
        )
            return "This alarm is due soon. Changes, disabling and deletion are locked during its final ${alarm.alarmProtectionMinutes} minutes."
        return null
    }
}

class AlarmProtection(private val context: Context) {
    fun reason(id: Int, now: Long = System.currentTimeMillis()): String? =
        synchronized(OccurrenceStore.lock) {
            AlarmProtectionRules.reason(
                AlarmStore(context).getAlarm(id),
                OccurrenceStore(context).all(),
                id,
                now,
            )
        }

    fun requireMutable(id: Int) {
        val reason = reason(id)
        check(reason == null) { checkNotNull(reason) }
    }
}
