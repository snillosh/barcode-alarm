package uk.co.barcodealarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** These tests assert navigation decisions without constructing any Android views. */
class NavigationTest {
    private val savedAlarm =
        Alarm(
            id = 1,
            hour = 7,
            minute = 30,
            barcodeValue = "code",
            barcodeFormat = "QR_CODE",
            soundUri = "sound",
            barcodeId = 3,
        )

    @Test
    fun settingsReturnsHome() {
        assertEquals(BackDestination.HOME, Navigation.back(Page.SETTINGS, false))
    }

    @Test
    fun libraryReturnsToItsCaller() {
        assertEquals(BackDestination.HOME, Navigation.back(Page.BARCODES, false))
        assertEquals(BackDestination.EDITOR, Navigation.back(Page.BARCODES, true))
    }

    @Test
    fun libraryNeverDiscardsChangedEditorDraft() {
        assertEquals(BackDestination.EDITOR, Navigation.back(Page.BARCODES, true, true))
    }

    @Test
    fun unchangedEditorNeedsNoPrompt() {
        assertEquals(
            BackDestination.HOME,
            Navigation.back(Page.EDITOR, true, Navigation.hasUnsavedChanges(savedAlarm, savedAlarm)),
        )
    }

    @Test
    fun changedEditorRequiresConfirmation() {
        assertEquals(
            BackDestination.CONFIRM_DISCARD,
            Navigation.back(
                Page.EDITOR,
                true,
                Navigation.hasUnsavedChanges(savedAlarm.copy(hour = 8), savedAlarm),
            ),
        )
    }

    @Test
    fun timeBarcodeAndSoundChangesAreProtected() {
        listOf(
                savedAlarm.copy(hour = 8),
                savedAlarm.copy(minute = 31),
                savedAlarm.copy(barcodeId = 4),
                savedAlarm.copy(soundUri = "new"),
            )
            .forEach { changedDraft ->
                assertTrue(Navigation.hasUnsavedChanges(changedDraft, savedAlarm))
            }
    }

    @Test
    fun unrelatedExecutionStateDoesNotCreateEdits() {
        assertFalse(
            Navigation.hasUnsavedChanges(
                savedAlarm.copy(
                    isEnabled = true,
                    scheduledAtMillis = 100,
                    isRinging = true,
                    correctAnswerCount = 5,
                    mathsStartedAtElapsedMillis = 200,
                ),
                savedAlarm,
            )
        )
    }

    @Test
    fun freshUntouchedAlarmDoesNotPrompt() {
        assertFalse(Navigation.hasUnsavedChanges(Alarm(8, 7, 0, "", ""), null))
    }

    @Test
    fun newBarcodeSelectionIsAnUnsavedEdit() {
        assertTrue(Navigation.hasUnsavedChanges(Alarm(8, 7, 0, "", "", barcodeId = 3), null))
    }

    @Test
    fun homeUsesAndroidDefault() {
        assertEquals(BackDestination.SYSTEM, Navigation.back(Page.HOME, false))
    }

    @Test
    fun restoresEachPageAndCallerDraft() {
        Page.entries.forEach { page -> assertEquals(page, Navigation.restorePage(page.name, true)) }
        assertEquals(
            BackDestination.EDITOR,
            Navigation.back(Navigation.restorePage("BARCODES", true), true),
        )
    }

    @Test
    fun invalidOrMissingEditorStateFallsBackToHome() {
        assertEquals(Page.HOME, Navigation.restorePage("EDITOR", false))
        assertEquals(Page.HOME, Navigation.restorePage("invalid", false))
        assertEquals(Page.HOME, Navigation.restorePage(null, false))
    }

    @Test
    fun mathsReturnsToRingingWhileRingingKeepsSystemControls() {
        assertEquals(BackDestination.RINGING, Navigation.ringingBack(true))
        assertEquals(BackDestination.SYSTEM, Navigation.ringingBack(false))
    }
}
