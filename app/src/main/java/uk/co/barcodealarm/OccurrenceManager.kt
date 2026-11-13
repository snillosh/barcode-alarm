package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.net.Uri
import android.os.SystemClock

/**
 * Persist first, then reconcile OS registrations. All callbacks re-read state and their deadline.
 */
class OccurrenceManager(private val context: Context) {
    private val store = OccurrenceStore(context)
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val events = listOf("ready", "timeout", "verify", "failed", "resume")

    private fun pending(occurrence: Occurrence, event: String, due: Long = 0): PendingIntent {
        val intent =
            Intent(context, OccurrenceReceiver::class.java)
                .setData(Uri.parse("barcodealarm://occurrence/${occurrence.id}/$event"))
                .putExtra("id", occurrence.id)
                .putExtra("event", event)
                .putExtra("due", due)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun open(occurrence: Occurrence) =
        PendingIntent.getActivity(
            context,
            occurrence.id,
            Intent(context, RingActivity::class.java)
                .putExtra("occurrence", occurrence.id)
                .setData(Uri.parse("barcodealarm://open/${occurrence.id}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun reconcile(occurrence: Occurrence) {
        events.forEach { manager.cancel(pending(occurrence, it)) }
        notifications.cancel(occurrence.id)
        for ((event, due) in OccurrenceRules.deadlines(occurrence)) {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    due.coerceAtLeast(System.currentTimeMillis() + 100),
                    open(occurrence),
                ),
                pending(occurrence, event, due),
            )
        }
        val title =
            when (occurrence.stage) {
                OccurrenceStage.SECOND_READY -> "Second barcode ready"
                OccurrenceStage.VERIFY_RESPONSE -> "Are you still awake?"
                else -> return
            }
        notifications.createNotificationChannel(
            NotificationChannel("followup", "Alarm follow-ups", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification =
            Notification.Builder(context, "followup")
                .setSmallIcon(R.drawable.ic_alarm_notification)
                .setContentTitle(title)
                .setContentText(
                    if (occurrence.stage == OccurrenceStage.SECOND_READY)
                        "Scan your second barcode to finish"
                    else "Confirm before the response deadline"
                )
                .setContentIntent(open(occurrence))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
        if (occurrence.stage == OccurrenceStage.VERIFY_RESPONSE)
            notification.addAction(
                Notification.Action.Builder(null, "I'm Awake", pending(occurrence, "awake")).build()
            )
        notifications.notify(occurrence.id, notification.build())
    }

    fun update(previous: Occurrence, next: Occurrence) {
        if (previous == next) return
        store.save(next)
        try {
            reconcile(next)
        } catch (exception: Exception) {
            store.save(previous)
            try {
                reconcile(previous)
            } catch (_: Exception) {}
            AlarmStore(context).log("Occurrence ${next.id} scheduling failed: $exception")
            throw exception
        }
    }

    fun scan(id: Int, value: String, format: String) {
        val occurrence = store.get(id) ?: return
        val wallRemaining = occurrence.eligibleAt - System.currentTimeMillis()
        val elapsedRemaining = occurrence.eligibleElapsed - SystemClock.elapsedRealtime()
        if (
            occurrence.stage in setOf(OccurrenceStage.SECOND_WAIT, OccurrenceStage.SECOND_READY) &&
                maxOf(wallRemaining, elapsedRemaining) > 0
        ) {
            error(
                "Second barcode is ready in ${(maxOf(wallRemaining, elapsedRemaining) + 999) / 1000} seconds"
            )
        }
        val next =
            OccurrenceRules.scan(
                occurrence,
                value,
                format,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
            )
        check(next != occurrence) {
            "Incorrect barcode or second barcode not ready. Wait for the countdown."
        }
        update(occurrence, next)
    }

    fun dismiss(id: Int, normal: Boolean = false) {
        val occurrence = store.get(id) ?: return
        val next =
            if (normal) OccurrenceRules.normalDismiss(occurrence, System.currentTimeMillis())
            else OccurrenceRules.dismiss(occurrence, System.currentTimeMillis())
        update(occurrence, next)
    }

    fun snooze(id: Int) {
        val occurrence = store.get(id) ?: return
        val next = OccurrenceRules.event(occurrence, "snooze", System.currentTimeMillis())
        try {
            update(occurrence, next)
        } catch (exception: Exception) {
            store.save(occurrence)
            throw exception
        }
    }

    fun event(id: Int, event: String, expectedDue: Long) {
        val occurrence = store.get(id) ?: return
        if (event != "awake" && OccurrenceRules.deadlines(occurrence)[event] != expectedDue) return
        val next = OccurrenceRules.event(occurrence, event, System.currentTimeMillis())
        update(occurrence, next)
        if (next.audible) context.startForegroundService(Intent(context, RingService::class.java))
    }

    fun cancelDefinition(id: Int) {
        store
            .all()
            .filter { it.definitionId == id }
            .forEach { occurrence ->
                store.save(occurrence.copy(stage = OccurrenceStage.COMPLETED))
                events.forEach { manager.cancel(pending(occurrence, it)) }
                notifications.cancel(occurrence.id)
            }
        if (store.all().none { it.audible })
            context.stopService(Intent(context, RingService::class.java))
    }

    fun migrateLegacy() {
        val definitions = AlarmStore(context)
        for (alarm in definitions.getAlarms().filter { it.isRinging || it.isSnoozed }) {
            val occurrence = store.create(alarm)
            val restored =
                if (alarm.isSnoozed)
                    occurrence.copy(
                        stage = OccurrenceStage.SNOOZED,
                        snoozeAt = alarm.scheduledAtMillis,
                    )
                else occurrence
            store.save(restored)
            definitions.saveAlarm(AlarmRules.markDismissed(alarm))
            if (alarm.repeatDays.isNotEmpty())
                AlarmScheduler(context).enable(alarm.copy(isRinging = false))
            reconcile(restored)
        }
    }

    fun restore(isBoot: Boolean) {
        store.all().forEach { old ->
            var occurrence = old
            if (isBoot) {
                occurrence =
                    OccurrenceRules.afterBoot(
                        occurrence,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(),
                    )
                store.save(occurrence)
            }
            reconcile(occurrence)
        }
    }
}

class OccurrenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            OccurrenceManager(context)
                .event(
                    intent.getIntExtra("id", -1),
                    intent.getStringExtra("event").orEmpty(),
                    intent.getLongExtra("due", 0),
                )
        } catch (exception: Exception) {
            AlarmStore(context).log("Occurrence event failed: $exception")
        }
    }
}
