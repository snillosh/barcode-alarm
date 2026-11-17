package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.os.*
import java.time.*
import java.util.TimeZone
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChargingServiceTest {
    private lateinit var context: Context
    private lateinit var original: TimeZone

    @Before
    fun before() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("charging.db")
        context
            .getSharedPreferences("charging_tracking", 0)
            .edit()
            .clear()
            .putBoolean("enabled", true)
            .commit()
        ChargingTracking.clock = Clock.fixed(Instant.parse("2026-11-17T20:00:00Z"), ZoneOffset.UTC)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun after() {
        context.deleteDatabase("charging.db")
        ChargingTracking.clock = Clock.systemUTC()
        TimeZone.setDefault(original)
    }

    @Test
    fun serviceRegistersPowerReceiverAndShowsSilentNotificationWithoutActivity() {
        val controller = Robolectric.buildService(ChargingTrackingService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
            assertSame(service, ChargingTrackingService.active)
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertEquals("Bedtime tracking active", notification.extras.getString("android.title"))
            assertNull(
                context
                    .getSystemService(NotificationManager::class.java)
                    .getNotificationChannel("bedtime-tracking")
                    .sound
            )
            context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(Looper.getMainLooper()).idle()
            ChargingStore(context).use {
                assertEquals(
                    "phone.power.connected",
                    it.pending(WellnessConnection.DEFAULT_SERVER)
                        .single()
                        .json()
                        .getString("eventType"),
                )
            }
        } finally {
            controller.destroy()
        }
        assertNull(ChargingTrackingService.active)
    }

    @Test
    fun clockRecoveryKeepsActiveReceiverAndSuppressesEstimate() {
        val controller = Robolectric.buildService(ChargingTrackingService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
            ChargingTracking.clock =
                Clock.fixed(Instant.parse("2026-11-17T21:00:00Z"), ZoneOffset.UTC)
            ChargingTracking.restore(context)
            assertSame(service, ChargingTrackingService.active)
            context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(Looper.getMainLooper()).idle()
            ChargingStore(context).use {
                assertEquals(1, it.pending(WellnessConnection.DEFAULT_SERVER).size)
                val end = Instant.parse("2026-11-18T03:00:00Z").toEpochMilli()
                it.finish(end, SystemClock.elapsedRealtime() + 6 * 3600000, true)
                assertEquals(1, it.pending(WellnessConnection.DEFAULT_SERVER).size)
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun reschedulingKeepsOneBoundaryAndDisablingCancelsIt() {
        ChargingTracking.clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)
        ChargingTracking.maintain(context)
        ChargingTracking.maintain(context)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(
            Instant.parse("2026-11-17T20:00:00Z").toEpochMilli(),
            alarms.nextScheduledAlarm!!.triggerAtTime,
        )
        ChargingTracking.setEnabled(context, false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
