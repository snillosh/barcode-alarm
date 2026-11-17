package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Persist first, then reconcile OS registrations. All callbacks re-read state and their deadline.
 */
class OccurrenceManager(private val context: Context) {
    private val store = OccurrenceStore(context)
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val events =
        listOf("ready", "timeout", "verify", "failed", "resume", "expire", "reminder")

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
            if (
                occurrence.source == OccurrenceSource.TIMER &&
                    occurrence.stage == OccurrenceStage.COUNTDOWN
            ) {
                if (event == "expire") {
                    // Full timer expiry uses the same alarm-clock delivery as normal alarms.
                    // Reminder idle quotas must never delay the final ringing event.
                    manager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(
                            System.currentTimeMillis() +
                                TimerRules.remaining(
                                        occurrence,
                                        System.currentTimeMillis(),
                                        SystemClock.elapsedRealtime(),
                                    )
                                    .coerceAtLeast(100),
                            open(occurrence),
                        ),
                        pending(occurrence, event, due),
                    )
                } else {
                    val trigger =
                        occurrence.timerExpiresElapsed - occurrence.timerDurationMillis +
                            checkNotNull(TimerRules.nextReminderOffset(occurrence))
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        trigger.coerceAtLeast(SystemClock.elapsedRealtime() + 100),
                        pending(occurrence, event, due),
                    )
                }
            } else
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
                        "${AlarmOverview.displayLabel(occurrence.snapshot)} · Scan your second barcode to finish"
                    else
                        "${AlarmOverview.displayLabel(occurrence.snapshot)} · Confirm before the response deadline"
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

    fun update(previous: Occurrence, next: Occurrence) =
        synchronized(OccurrenceStore.lock) {
            if (previous == next) return@synchronized
            try {
                CompletionTransition.apply(previous, next, store, ::reconcile)
            } catch (exception: Exception) {
                AlarmStore(context).log("Occurrence ${next.id} scheduling failed")
                throw exception
            }
            if (next.stage == OccurrenceStage.COMPLETED && next.source == OccurrenceSource.ALARM)
                WellnessSync.requestSafely(context)
        }

    fun recoverCompletions() =
        synchronized(OccurrenceStore.lock) {
            for (record in store.history().filter { it.recovery != null }) {
                reconcile(checkNotNull(record.recovery))
                store.settleCompletion(record.event.eventId)
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
        val next =
            OccurrenceRules.event(
                occurrence,
                event,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
            )
        update(occurrence, next)
        if (next == occurrence && occurrence.stage == OccurrenceStage.COUNTDOWN)
            reconcile(occurrence)
        if (event == "reminder" && next != occurrence) postTimerReminder(next)
        if (next.audible) context.startForegroundService(Intent(context, RingService::class.java))
    }

    fun cancelDefinition(id: Int) {
        store
            .all()
            .filter { it.belongsTo(OccurrenceSource.ALARM, id) }
            .forEach { occurrence ->
                store.save(occurrence.copy(stage = OccurrenceStage.COMPLETED))
                events.forEach { manager.cancel(pending(occurrence, it)) }
                notifications.cancel(occurrence.id)
            }
        if (store.all().none { it.audible })
            context.stopService(Intent(context, RingService::class.java))
    }

    fun startTimer(preset: TimerPreset): Occurrence {
        check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            "Allow exact alarms in Settings first"
        }
        check(
            store.all().none {
                it.source == OccurrenceSource.TIMER && it.definitionId == preset.id && !it.terminal
            }
        ) {
            "This timer is already running or awaiting dismissal"
        }
        val codes = AlarmStore(context).getBarcodes()
        val configuration =
            preset.configuration.copy(
                firstBarcodeName =
                    codes.find { it.id == preset.configuration.barcodeId }?.name.orEmpty(),
                secondBarcodeName =
                    codes.find { it.id == preset.configuration.secondBarcodeId }?.name.orEmpty(),
            )
        val occurrence =
            TimerRules.start(
                preset.copy(configuration = configuration),
                store.nextId(),
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
            )
        val identified =
            occurrence.copy(
                timerBootCount =
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            )
        store.save(identified)
        try {
            reconcile(identified)
        } catch (exception: Exception) {
            reconcile(occurrence.copy(stage = OccurrenceStage.CANCELLED))
            store.remove(occurrence.id)
            throw exception
        }
        return identified
    }

    fun cancelTimer(id: Int) {
        val occurrence = store.get(id) ?: return
        if (occurrence.terminal) return
        val cancelled =
            TimerRules.cancel(occurrence, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (cancelled == occurrence) {
            // Cancellation after the deadline must not become a barcode bypass, even if
            // Android has not delivered the expiration broadcast yet.
            if (
                occurrence.source == OccurrenceSource.TIMER &&
                    occurrence.stage == OccurrenceStage.COUNTDOWN
            )
                event(id, "expire", occurrence.timerExpiresAt)
            error("This timer has expired. Use its dismissal screen.")
        }
        update(occurrence, cancelled)
    }

    fun deleteTimerPreset(id: Int) {
        val related = store.all().filter { it.belongsTo(OccurrenceSource.TIMER, id) }
        check(
            related.none {
                !it.terminal &&
                    (it.stage != OccurrenceStage.COUNTDOWN ||
                        TimerRules.remaining(
                            it,
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(),
                        ) == 0L)
            }
        ) {
            "Dismiss the expired timer before deleting its preset"
        }
        related.forEach {
            if (!it.terminal) cancelTimer(it.id)
            reconcile(it.copy(stage = OccurrenceStage.CANCELLED))
            store.remove(it.id)
        }
        TimerStore(context).delete(id)
    }

    private fun postTimerReminder(occurrence: Occurrence) {
        val channel =
            NotificationChannel(
                "timer-reminders",
                "Timer interval reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.setSound(
            Settings.System.DEFAULT_NOTIFICATION_URI,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
        )
        notifications.createNotificationChannel(channel)
        val openTimers =
            PendingIntent.getActivity(
                context,
                occurrence.id,
                Intent(context, TimerActivity::class.java)
                    .setData(Uri.parse("barcodealarm://timer/${occurrence.id}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notifications.notify(
            occurrence.id,
            Notification.Builder(context, "timer-reminders")
                .setSmallIcon(R.drawable.ic_alarm_notification)
                .setContentTitle(AlarmOverview.displayLabel(occurrence.snapshot))
                .setContentText(
                    "${TimerRules.countdown(TimerRules.remaining(occurrence, System.currentTimeMillis(), SystemClock.elapsedRealtime()))} remaining"
                )
                .setContentIntent(openTimers)
                .setAutoCancel(true)
                .build(),
        )
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
        try {
            recoverCompletions()
        } catch (_: Exception) {
            store.reportCaptureFailure()
        }
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
            if (
                occurrence.source == OccurrenceSource.TIMER &&
                    occurrence.stage == OccurrenceStage.COUNTDOWN
            ) {
                val bootCount =
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                val changedBoot =
                    isBoot ||
                        (occurrence.timerBootCount >= 0 &&
                            bootCount >= 0 &&
                            occurrence.timerBootCount != bootCount)
                occurrence =
                    TimerRules.restore(
                            occurrence,
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(),
                            changedBoot,
                        )
                        .copy(timerBootCount = bootCount)
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
