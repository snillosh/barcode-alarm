package uk.co.barcodealarm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
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
class SnoozeAndProtectionAndroidTest {
    private lateinit var context: Context
    private lateinit var store: OccurrenceStore
    private lateinit var manager: OccurrenceManager

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        for (name in
            listOf("alarms", "timers", "occurrences", "timer_settings", "wellness_sync")) context
            .getSharedPreferences(name, 0)
            .edit()
            .clear()
            .commit()
        store = OccurrenceStore(context)
        manager = OccurrenceManager(context)
    }

    private fun preset(id: Int = 1, snooze: Boolean = false) =
        TimerPreset(id, 600_000, Alarm(id, 0, 0, "", "", label = "Bath", canBeSnoozed = snooze))

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun settingsPersistAndEachStartedRunKeepsItsOwnProtection() {
        assertEquals(1, TimerSettings(context).cancellationLockMinutes())
        TimerSettings(context).saveCancellationLockMinutes(2)
        val first = manager.startTimer(preset())
        assertEquals(2, first.timerCancellationLockMinutes)
        TimerSettings(context).saveCancellationLockMinutes(0)
        val next = manager.startTimer(preset(2))
        assertEquals(2, store.get(first.id)!!.timerCancellationLockMinutes)
        assertEquals(0, next.timerCancellationLockMinutes)
        assertEquals(0, TimerSettings(context).cancellationLockMinutes())
    }

    @Test
    fun cancellationAndPresetDeletionBothRejectProtectedWindowWithoutMutatingTheRun() {
        val preset = preset()
        TimerStore(context).save(preset)
        val run =
            TimerRules.start(
                    preset,
                    1000000,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                )
                .copy(timerCancellationLockMinutes = 20)
        store.save(run)
        assertThrows(IllegalStateException::class.java) { manager.cancelTimer(run.id) }
        assertEquals(run, store.get(run.id))
        assertThrows(IllegalStateException::class.java) { manager.deleteTimerPreset(preset.id) }
        assertEquals(run, store.get(run.id))
        assertEquals(preset, TimerStore(context).all().single())
    }

    @Test
    fun unprotectedCancellationStillClearsAllDeadlines() {
        TimerSettings(context).saveCancellationLockMinutes(0)
        val run = manager.startTimer(preset())
        manager.cancelTimer(run.id)
        val cancelled = store.get(run.id)!!
        assertEquals(OccurrenceStage.CANCELLED, cancelled.stage)
        assertTrue(OccurrenceRules.deadlines(cancelled).isEmpty())
        manager.cancelTimer(run.id)
        assertEquals(cancelled, store.get(run.id))
    }

    @Test
    fun managerRejectsDisabledSnoozeAndSnapshotDoesNotFollowPresetEdits() {
        val preset = preset()
        TimerStore(context).save(preset)
        val ringing =
            TimerRules.start(preset, 1000000, 100, 100).copy(stage = OccurrenceStage.INITIAL)
        store.save(ringing)
        TimerStore(context)
            .save(preset.copy(configuration = preset.configuration.copy(canBeSnoozed = true)))
        assertThrows(IllegalStateException::class.java) { manager.snooze(ringing.id) }
        assertEquals(ringing, store.get(ringing.id))
    }

    @Test
    fun snoozedTimerDismissalClearsResumeWithoutCreatingWellnessHistory() {
        val ringing =
            TimerRules.start(preset(snooze = true), 1000000, 100, 100)
                .copy(stage = OccurrenceStage.INITIAL)
        store.save(ringing)
        manager.snooze(ringing.id)
        assertEquals(OccurrenceStage.SNOOZED, store.get(ringing.id)!!.stage)
        manager.dismiss(ringing.id, normal = true)
        assertEquals(OccurrenceStage.COMPLETED, store.get(ringing.id)!!.stage)
        assertTrue(store.history().isEmpty())
        assertTrue(OccurrenceRules.deadlines(store.get(ringing.id)!!).isEmpty())
    }

    @Test
    fun ringingScreenHidesSnoozeWhenDisabledAndShowsItWhenEnabled() {
        for (enabled in listOf(false, true)) {
            val run =
                TimerRules.start(preset(snooze = enabled), 1000000, 100, 100)
                    .copy(stage = OccurrenceStage.INITIAL)
            store.save(run)
            val controller =
                Robolectric.buildActivity(
                        RingActivity::class.java,
                        Intent(context, RingActivity::class.java).putExtra("occurrence", run.id),
                    )
                    .setup()
            try {
                val buttons = views(controller.get().window.decorView).filterIsInstance<Button>()
                assertEquals(enabled, buttons.any { it.text.startsWith("Snooze ·") })
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun snoozedTimerScreenOffersImmediateDismissWithoutStartingPlayback() {
        val ringing =
            TimerRules.start(preset(snooze = true), 1000000, 100, 100)
                .copy(stage = OccurrenceStage.INITIAL)
        val snoozed = OccurrenceRules.event(ringing, "snooze", System.currentTimeMillis())
        store.save(snoozed)
        val controller =
            Robolectric.buildActivity(
                    RingActivity::class.java,
                    Intent(context, RingActivity::class.java).putExtra("occurrence", snoozed.id),
                )
                .setup()
        try {
            val buttons = views(controller.get().window.decorView).filterIsInstance<Button>()
            assertTrue(buttons.any { it.text == "Dismiss" })
            assertFalse(buttons.any { it.text.startsWith("Snooze ·") })
            assertTrue(shadowOf(context as Application).nextStartedService == null)
            buttons.single { it.text == "Dismiss" }.performClick()
            assertEquals(OccurrenceStage.COMPLETED, store.get(snoozed.id)!!.stage)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun timerEditorCheckboxDefaultsOffAndPersistsItsChoice() {
        val preset = preset()
        TimerStore(context).save(preset)
        val controller = Robolectric.buildActivity(TimerActivity::class.java).setup()
        try {
            val activity = controller.get()
            views(activity.window.decorView)
                .filterIsInstance<Button>()
                .single { it.text == "Edit" }
                .performClick()
            val checkbox =
                views(activity.window.decorView).filterIsInstance<CheckBox>().single {
                    it.text == "Can be snoozed"
                }
            assertFalse(checkbox.isChecked)
            checkbox.isChecked = true
            views(activity.window.decorView)
                .filterIsInstance<Button>()
                .single { it.text == "Save timer" }
                .performClick()
            assertTrue(TimerStore(context).all().single().configuration.canBeSnoozed)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun visibleCancelControlDisappearsAtTheProtectedBoundary() {
        val preset = preset()
        TimerStore(context).save(preset)
        val run =
            TimerRules.start(
                    preset,
                    1000000,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                )
                .copy(
                    timerExpiresElapsed = SystemClock.elapsedRealtime() + 60_500,
                    timerExpiresAt = System.currentTimeMillis() + 60_500,
                    timerCancellationLockMinutes = 1,
                )
        store.save(run)
        val controller = Robolectric.buildActivity(TimerActivity::class.java).setup()
        try {
            assertTrue(
                views(controller.get().window.decorView).filterIsInstance<Button>().any {
                    it.text == "Cancel countdown"
                }
            )
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(
                views(controller.get().window.decorView).filterIsInstance<Button>().any {
                    it.text == "Cancel countdown"
                }
            )
            assertEquals(OccurrenceStage.COUNTDOWN, store.get(run.id)!!.stage)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun settingsRejectInvalidDurations() {
        assertThrows(IllegalArgumentException::class.java) {
            TimerSettings(context).saveCancellationLockMinutes(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerSettings(context).saveCancellationLockMinutes(61)
        }
    }

    @Test
    fun alarmEditorPreservesCheckboxThroughRecreationAndSaving() {
        AlarmStore(context).saveAlarm(Alarm(1, 7, 0, "", "", canBeSnoozed = false))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            views(controller.get().window.decorView)
                .filterIsInstance<Button>()
                .single { it.text == "Edit alarm" }
                .performClick()
            val initial =
                views(controller.get().window.decorView).filterIsInstance<CheckBox>().single {
                    it.text == "Can be snoozed"
                }
            assertFalse(initial.isChecked)
            initial.isChecked = true
            controller.recreate()
            val restored =
                views(controller.get().window.decorView).filterIsInstance<CheckBox>().single {
                    it.text == "Can be snoozed"
                }
            assertTrue(restored.isChecked)
            views(controller.get().window.decorView)
                .filterIsInstance<Button>()
                .single { it.text == "Save alarm" }
                .performClick()
            assertTrue(AlarmStore(context).getAlarm(1)!!.canBeSnoozed)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun settingsScreenSavesTimerProtectionSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            views(controller.get().window.decorView)
                .filterIsInstance<Button>()
                .single { it.text == "Settings & diagnostics" }
                .performClick()
            val selector =
                views(controller.get().window.decorView).filterIsInstance<Spinner>().single {
                    it.adapter.count == 61
                }
            selector.setSelection(3)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, TimerSettings(context).cancellationLockMinutes())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
