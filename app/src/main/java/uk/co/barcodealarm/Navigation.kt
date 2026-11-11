package uk.co.barcodealarm

/** Small navigation rules independent of Android widgets and alarm execution. */
enum class Page { HOME, SETTINGS, EDITOR, BARCODES }
enum class BackDestination { SYSTEM, HOME, EDITOR, CONFIRM_DISCARD, RINGING }
object Navigation {
 fun restorePage(name: String?, hasDraft: Boolean): Page {
  val page=Page.entries.find { it.name==name } ?: Page.HOME
  return if(page==Page.EDITOR && !hasDraft) Page.HOME else page
 }
 fun back(page: Page, hasDraft: Boolean, changed: Boolean=false): BackDestination = when(page) {
  Page.HOME -> BackDestination.SYSTEM
  Page.SETTINGS -> BackDestination.HOME
  Page.BARCODES -> if(hasDraft) BackDestination.EDITOR else BackDestination.HOME
  Page.EDITOR -> if(changed) BackDestination.CONFIRM_DISCARD else BackDestination.HOME
 }
 fun changed(draft: Alarm, saved: Alarm?): Boolean {
  val original=saved ?: Alarm(draft.id,7,0,"","")
  return draft.hour!=original.hour || draft.minute!=original.minute || draft.barcodeId!=original.barcodeId || draft.sound!=original.sound
 }
 fun ringingBack(maths: Boolean)=if(maths) BackDestination.RINGING else BackDestination.SYSTEM
}
