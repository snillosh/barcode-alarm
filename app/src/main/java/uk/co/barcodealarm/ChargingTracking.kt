package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat
import java.time.ZoneId

object ChargingTracking {
    private const val REQUEST = 430001
    internal var clock: java.time.Clock = java.time.Clock.systemUTC()

    fun offerSetup(activity: Activity) {
        val prefs = activity.getSharedPreferences("charging_tracking", 0)
        if (prefs.contains("enabled") || activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Enable bedtime charging tracking?")
            .setMessage(
                "Record charging changes from 20:00 to 03:00 and estimate bedtime when the last new connection remains connected. A silent nightly notification runs while the screen is closed. Wellness Sync uploads saved observations when enabled."
            )
            .setPositiveButton("Enable") { _, _ ->
                try {
                    setEnabled(activity, true)
                    activity.startActivity(Intent(activity, ChargingSettingsActivity::class.java))
                } catch (_: Exception) {
                    status(activity, "Open bedtime charging settings to finish setup")
                }
            }
            .setNegativeButton("Not now") { _, _ -> setEnabled(activity, false) }
            .setCancelable(false)
            .show()
    }

    fun enabled(context: Context) =
        context.getSharedPreferences("charging_tracking", 0).getBoolean("enabled", false)

    fun status(context: Context) =
        context.getSharedPreferences("charging_tracking", 0).getString("status", "Not enabled")!!

    fun status(context: Context, message: String) {
        context
            .getSharedPreferences("charging_tracking", 0)
            .edit()
            .putString("status", message)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(
            context
                .getSharedPreferences("charging_tracking", 0)
                .edit()
                .putBoolean("enabled", enabled)
                .commit()
        )
        if (!enabled) {
            context.stopService(Intent(context, ChargingTrackingService::class.java))
            ChargingStore(context).use { it.invalidate() }
        }
        maintain(context)
    }

    fun maintain(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                REQUEST,
                Intent(context, ChargingBoundaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.cancel(pending)
        if (!enabled(context)) {
            status(context, "Disabled; saved observations retained")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            status(context, "Allow Alarms & reminders to schedule nightly tracking")
            return
        }
        val now = ChargingTracking.clock.millis()
        val zone = ZoneId.systemDefault()
        val window = ChargingRules.window(now, zone)
        if (window.contains(now) && ChargingTrackingService.active == null) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ChargingTrackingService::class.java),
                )
            } catch (_: Exception) {
                ChargingStore(context).use { it.invalidate() }
                status(context, "Open the app to resume tracking; this night may have no estimate")
            }
        }
        val next = if (window.contains(now)) window.end else ChargingRules.nextStart(now, zone)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        if (!window.contains(now)) status(context, "Scheduled for 20:00–03:00 · local time")
    }

    fun restore(context: Context) {
        try {
            ChargingStore(context).use { it.invalidate() }
            context.stopService(Intent(context, ChargingTrackingService::class.java))
            maintain(context)
        } catch (_: Exception) {
            status(context, "Tracking needs attention; open charging settings")
        }
    }
}

class ChargingBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val now = ChargingTracking.clock.millis()
            if (!ChargingRules.window(now, ZoneId.systemDefault()).contains(now)) {
                ChargingTrackingService.active?.finishWindow()
                // If no service survived, continuity cannot be proved.
                if (ChargingTrackingService.active == null)
                    ChargingStore(context).use { it.invalidate() }
            }
            ChargingTracking.maintain(context)
        } catch (_: Exception) {
            ChargingTracking.status(context, "Nightly tracking interrupted; open the app")
        }
    }
}

class ChargingTrackingService : Service() {
    companion object {
        var active: ChargingTrackingService? = null
            private set
    }

    private var initialized = false
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (
                    intent.action != Intent.ACTION_POWER_CONNECTED &&
                        intent.action != Intent.ACTION_POWER_DISCONNECTED
                )
                    return
                try {
                    val battery = battery()
                    val powered = intent.action == Intent.ACTION_POWER_CONNECTED
                    ChargingStore(this@ChargingTrackingService).use {
                        if (powered != (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0))
                            it.invalidate()
                        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val percent =
                            if (level >= 0 && scale > 0)
                                (100L * level / scale).toInt().coerceIn(0, 100)
                            else null
                        val charger =
                            when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                                8 -> "dock"
                                else -> "unknown"
                            }
                        it.observe(
                            ChargingTracking.clock.millis(),
                            SystemClock.elapsedRealtime(),
                            powered,
                            percent,
                            charger,
                        )
                    }
                    WellnessSync.requestSafely(this@ChargingTrackingService)
                } catch (_: Exception) {
                    try {
                        ChargingStore(this@ChargingTrackingService).use { it.invalidate() }
                    } catch (_: Exception) {}
                    ChargingTracking.status(
                        this@ChargingTrackingService,
                        "Observation capture interrupted; no bedtime estimate",
                    )
                }
            }
        }

    private fun battery() =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: Intent()

    override fun onCreate() {
        super.onCreate()
        val channel =
            NotificationChannel(
                    "bedtime-tracking",
                    "Bedtime tracking",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open =
            PendingIntent.getActivity(
                this,
                430002,
                Intent(this, ChargingSettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(this, "bedtime-tracking")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("Bedtime tracking active")
                .setContentText("Recording charging changes until 03:00")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(430003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(430003, notification)
        active = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val now = ChargingTracking.clock.millis()
            val window = ChargingRules.window(now, ZoneId.systemDefault())
            if (!ChargingTracking.enabled(this) || !window.contains(now)) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (!initialized) {
                ChargingStore(this).use {
                    it.begin(
                        window,
                        now,
                        battery().getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                        SystemClock.elapsedRealtime(),
                    )
                }
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_POWER_CONNECTED)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
                initialized = true
                ChargingTracking.status(this, "Tracking charging changes · 20:00–03:00")
            }
            ChargingTracking.maintain(this)
            return START_STICKY
        } catch (_: Exception) {
            ChargingTracking.status(this, "Tracking interrupted; no estimate for this night")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    fun finishWindow() {
        try {
            ChargingStore(this).use {
                it.finish(
                    ChargingTracking.clock.millis(),
                    SystemClock.elapsedRealtime(),
                    battery().getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                )
            }
            WellnessSync.requestSafely(this)
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
