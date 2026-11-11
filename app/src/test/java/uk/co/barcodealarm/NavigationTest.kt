package uk.co.barcodealarm

import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
 private val saved=Alarm(1,7,30,"code","QR_CODE",sound="sound",barcodeId=3)
 @Test fun settingsReturnsHome() { assertEquals(BackDestination.HOME,Navigation.back(Page.SETTINGS,false)) }
 @Test fun libraryReturnsToItsCaller() {
  assertEquals(BackDestination.HOME,Navigation.back(Page.BARCODES,false))
  assertEquals(BackDestination.EDITOR,Navigation.back(Page.BARCODES,true))
 }
 @Test fun libraryNeverDiscardsChangedEditorDraft() { assertEquals(BackDestination.EDITOR,Navigation.back(Page.BARCODES,true,true)) }
 @Test fun unchangedEditorNeedsNoPrompt() { assertEquals(BackDestination.HOME,Navigation.back(Page.EDITOR,true,Navigation.changed(saved,saved))) }
 @Test fun changedEditorRequiresConfirmation() { assertEquals(BackDestination.CONFIRM_DISCARD,Navigation.back(Page.EDITOR,true,Navigation.changed(saved.copy(hour=8),saved))) }
 @Test fun timeBarcodeAndSoundChangesAreProtected() {
  listOf(saved.copy(hour=8),saved.copy(minute=31),saved.copy(barcodeId=4),saved.copy(sound="new")).forEach { assertTrue(Navigation.changed(it,saved)) }
 }
 @Test fun unrelatedExecutionStateDoesNotCreateEdits() { assertFalse(Navigation.changed(saved.copy(enabled=true,due=100,ringing=true,correct=5,started=200),saved)) }
 @Test fun freshUntouchedAlarmDoesNotPrompt() { assertFalse(Navigation.changed(Alarm(8,7,0,"",""),null)) }
 @Test fun newBarcodeSelectionIsAnUnsavedEdit() { assertTrue(Navigation.changed(Alarm(8,7,0,"","",barcodeId=3),null)) }
 @Test fun homeUsesAndroidDefault() { assertEquals(BackDestination.SYSTEM,Navigation.back(Page.HOME,false)) }
 @Test fun restoresEachPageAndCallerDraft() {
  Page.entries.forEach { assertEquals(it,Navigation.restorePage(it.name,true)) }
  assertEquals(BackDestination.EDITOR,Navigation.back(Navigation.restorePage("BARCODES",true),true))
 }
 @Test fun invalidOrMissingEditorStateFallsBackToHome() {
  assertEquals(Page.HOME,Navigation.restorePage("EDITOR",false))
  assertEquals(Page.HOME,Navigation.restorePage("invalid",false))
  assertEquals(Page.HOME,Navigation.restorePage(null,false))
 }
 @Test fun mathsReturnsToRingingWhileRingingKeepsSystemControls() {
  assertEquals(BackDestination.RINGING,Navigation.ringingBack(true))
  assertEquals(BackDestination.SYSTEM,Navigation.ringingBack(false))
 }
}
