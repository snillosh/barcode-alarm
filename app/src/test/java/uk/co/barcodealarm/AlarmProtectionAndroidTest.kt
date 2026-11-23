package uk.co.barcodealarm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AlarmProtectionAndroidTest {
    private lateinit var context: Context
    private val now
        get() = System.currentTimeMillis()

    private fun scheduled(due: Long = now + 120_000) =
        Alarm(1, 7, 0, "", "", label = "Work", isEnabled = true, scheduledAtMillis = due)

    private fun snoozed() =
        Occurrence(
            1000000,
            1,
            scheduled()
                .copy(id = 1000000, isRinging = true, occurrenceScheduledAtMillis = now - 60_000),
            stage = OccurrenceStage.SNOOZED,
            snoozeAt = now + 300_000,
        )

    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        for (name in
            listOf("alarms", "occurrences", "alarm_settings", "timers", "wellness_sync")) context
            .getSharedPreferences(name, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun guardedDeletionNeverCancelsOrDeletesNearDueDefinition() {
        val store = AlarmStore(context)
        val alarm = AlarmScheduler(context).enable(scheduled(), now + 120_000)
        assertThrows(IllegalStateException::class.java) { AlarmScheduler(context).delete(alarm.id) }
        assertEquals(alarm, store.getAlarm(alarm.id))
        assertThrows(IllegalStateException::class.java) { store.deleteAlarm(alarm.id) }
        assertEquals(alarm, store.getAlarm(alarm.id))
    }

    @Test
    fun snoozedOneShotCannotBeDeletedDisabledOrRescheduledUsingStaleDefinition() {
        val definition = scheduled().copy(isEnabled = false, scheduledAtMillis = 0)
        AlarmStore(context).saveAlarm(definition)
        val run = snoozed()
        OccurrenceStore(context).save(run)
        val scheduler = AlarmScheduler(context)
        assertThrows(IllegalStateException::class.java) { scheduler.delete(definition.id) }
        assertThrows(IllegalStateException::class.java) { scheduler.disable(definition) }
        assertThrows(IllegalStateException::class.java) {
            scheduler.enableFromUser(definition.copy(hour = 20))
        }
        assertThrows(IllegalStateException::class.java) {
            scheduler.saveFromUser(definition.copy(label = "Changed"))
        }
        assertEquals(definition, AlarmStore(context).getAlarm(definition.id))
        assertEquals(run, OccurrenceStore(context).get(run.id))
        assertTrue(OccurrenceStore(context).history().isEmpty())
    }

    @Test
    fun previouslyRenderedFarFutureRecordCannotBypassNewProtectedState() {
        val original = scheduled(now + 3_600_000)
        AlarmStore(context).saveAlarm(original)
        val due = original.copy(scheduledAtMillis = now + 120_000)
        AlarmStore(context).saveAlarm(due)
        assertThrows(IllegalStateException::class.java) {
            AlarmScheduler(context).disable(original)
        }
        assertThrows(IllegalStateException::class.java) {
            AlarmScheduler(context).enableFromUser(original)
        }
        assertEquals(due, AlarmStore(context).getAlarm(due.id))
    }

    @Test
    fun editProtectionSettingDoesNotUnlockAnAlreadyEnabledAlarm() {
        val settings = AlarmSettings(context)
        assertEquals(5, settings.protectionMinutes())
        val alarm = AlarmScheduler(context).enable(scheduled(), now + 240_000)
        settings.saveProtectionMinutes(1)
        assertEquals(5, AlarmStore(context).getAlarm(alarm.id)!!.alarmProtectionMinutes)
        assertThrows(IllegalStateException::class.java) { AlarmScheduler(context).delete(alarm.id) }
        val next = AlarmScheduler(context).enable(scheduled().copy(id = 2), now + 600_000)
        assertEquals(1, next.alarmProtectionMinutes)
    }

    @Test
    fun unprotectedDeletionAndDisableContinueWorking() {
        val alarm = AlarmScheduler(context).enable(scheduled(), now + 3_600_000)
        AlarmScheduler(context).disable(alarm)
        assertFalse(AlarmStore(context).getAlarm(alarm.id)!!.isEnabled)
        AlarmScheduler(context).delete(alarm.id)
        assertNull(AlarmStore(context).getAlarm(alarm.id))
    }

    @Test
    fun internalRepeatSchedulingWorksWhilePreviousOccurrenceIsSnoozed() {
        val run = snoozed()
        OccurrenceStore(context).save(run)
        val future = AlarmScheduler(context).enable(scheduled(), now + 86_400_000)
        assertTrue(future.isEnabled)
        assertEquals(run, OccurrenceStore(context).get(run.id))
    }

    @Test
    fun timerIdentityCannotLockUnrelatedAlarmWithSameNumericDefinitionId() {
        AlarmStore(context).saveAlarm(scheduled(now + 3_600_000))
        val timer = snoozed().copy(source = OccurrenceSource.TIMER)
        OccurrenceStore(context).save(timer)
        AlarmScheduler(context).delete(1)
        assertNull(AlarmStore(context).getAlarm(1))
        assertEquals(timer, OccurrenceStore(context).get(timer.id))
    }

    @Test
    fun homeShowsSnoozeAsActiveWithDeadlineAndLockedControls() {
        val definition = scheduled().copy(isEnabled = false, scheduledAtMillis = 0)
        AlarmStore(context).saveAlarm(definition)
        OccurrenceStore(context).save(snoozed())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val all = views(controller.get().window.decorView)
            assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("Snoozed until") })
            assertFalse(all.filterIsInstance<TextView>().any { it.text == "No upcoming alarms" })
            assertTrue(all.filterIsInstance<Button>().any { it.text == "Open snoozed alarm" })
            assertFalse(
                all.filterIsInstance<Button>().any {
                    it.text == "Delete" || it.text == "Edit alarm"
                }
            )
            val toggle = all.filterIsInstance<Switch>().single()
            assertTrue(toggle.isChecked)
            assertFalse(toggle.isEnabled)
            assertEquals(definition, AlarmStore(context).getAlarm(definition.id))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun visibleHomeRemovesDeleteAtExactProtectionBoundary() {
        val start = now
        AlarmStore(context).saveAlarm(scheduled(start + 300_500))
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().homeClock =
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(start), java.time.ZoneOffset.UTC)
        controller.setup()
        try {
            assertTrue(
                views(controller.get().window.decorView).filterIsInstance<Button>().any {
                    it.text == "Delete"
                }
            )
            controller.get().homeClock =
                java.time.Clock.fixed(
                    java.time.Instant.ofEpochMilli(start + 2000),
                    java.time.ZoneOffset.UTC,
                )
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(
                views(controller.get().window.decorView).filterIsInstance<Button>().any {
                    it.text == "Delete"
                }
            )
            assertTrue(AlarmStore(context).getAlarm(1)!!.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun homeRefreshesWhenAnOccurrenceBecomesSnoozedWhileVisible() {
        AlarmStore(context).saveAlarm(scheduled().copy(isEnabled = false, scheduledAtMillis = 0))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(
                views(controller.get().window.decorView).filterIsInstance<TextView>().any {
                    it.text == "No upcoming alarms"
                }
            )
            OccurrenceStore(context).save(snoozed())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val all = views(controller.get().window.decorView)
            assertFalse(all.filterIsInstance<TextView>().any { it.text == "No upcoming alarms" })
            assertTrue(all.filterIsInstance<Button>().any { it.text == "Open snoozed alarm" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun snoozedAlarmOpensSilentDismissalScreenAndCompletesNormally() {
        val run = snoozed()
        OccurrenceStore(context).save(run)
        val controller =
            Robolectric.buildActivity(
                    RingActivity::class.java,
                    Intent(context, RingActivity::class.java).putExtra("occurrence", run.id),
                )
                .setup()
        try {
            val all = views(controller.get().window.decorView)
            assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("Snoozed ·") })
            assertNull(shadowOf(context as Application).nextStartedService)
            all.filterIsInstance<Button>().single { it.text == "Dismiss" }.performClick()
            assertNull(OccurrenceStore(context).get(run.id))
            assertEquals(1, OccurrenceStore(context).history().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun protectedDefinitionIsEditableAgainAfterProperDismissal() {
        val definition = scheduled().copy(isEnabled = false, scheduledAtMillis = 0)
        AlarmStore(context).saveAlarm(definition)
        val run = snoozed()
        OccurrenceStore(context).save(run)
        OccurrenceManager(context).dismiss(run.id, normal = true)
        AlarmScheduler(context).delete(definition.id)
        assertNull(AlarmStore(context).getAlarm(definition.id))
        assertEquals(1, OccurrenceStore(context).history().size)
    }

    @Test
    fun alarmSettingsRejectDisabledOrInvalidProtectionWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            AlarmSettings(context).saveProtectionMinutes(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlarmSettings(context).saveProtectionMinutes(61)
        }
    }

    @Test
    fun snoozingReturnsToHomeWithoutLosingTheActiveOccurrence() {
        val definition = scheduled().copy(isEnabled = false, scheduledAtMillis = 0)
        AlarmStore(context).saveAlarm(definition)
        val run = snoozed().copy(stage = OccurrenceStage.INITIAL)
        OccurrenceStore(context).save(run)
        val controller =
            Robolectric.buildActivity(
                    RingActivity::class.java,
                    Intent(context, RingActivity::class.java).putExtra("occurrence", run.id),
                )
                .setup()
        try {
            val activity = controller.get()
            views(activity.window.decorView)
                .filterIsInstance<Button>()
                .single { it.text.startsWith("Snooze ·") }
                .performClick()
            assertEquals(OccurrenceStage.SNOOZED, OccurrenceStore(context).get(run.id)!!.stage)
            assertTrue(activity.isFinishing)
            assertEquals(
                MainActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component!!.className,
            )
            assertNotNull(AlarmProtection(context).reason(definition.id))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
