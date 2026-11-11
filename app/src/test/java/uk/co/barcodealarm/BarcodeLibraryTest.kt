package uk.co.barcodealarm

import org.junit.Assert.*
import org.junit.Test

class BarcodeLibraryTest {
 private val shampoo=SavedBarcode(1,"Home bathroom shampoo","123","QR_CODE")
 private val alarm=Alarm(5,7,30,"old","QR_CODE",sound="sound",enabled=true,due=123456)
 private fun rejects(action: ()->Unit) { assertThrows(IllegalArgumentException::class.java,action) }
 @Test fun addingTrimsNameAndKeepsScannedCode() {
  val saved=BarcodeLibrary.add(emptyList(),"  Shampoo  "," value ","QR_CODE").single()
  assertEquals("Shampoo",saved.name);assertEquals(" value ",saved.code);assertEquals(1,saved.id)
 }
 @Test fun namesMustNotBeBlank() { rejects { BarcodeLibrary.add(emptyList(),"  ","123","QR_CODE") } }
 @Test fun namesHaveReasonableLimit() { rejects { BarcodeLibrary.add(emptyList(),"x".repeat(121),"123","QR_CODE") } }
 @Test fun namesCannotBeAmbiguousInDropdown() { rejects { BarcodeLibrary.add(listOf(shampoo),"HOME BATHROOM SHAMPOO","other","QR_CODE") } }
 @Test fun rejectsUnscannedCode() { rejects { BarcodeLibrary.add(emptyList(),"Shampoo","","QR_CODE") } }
 @Test fun rejectsDuplicateCode() { rejects { BarcodeLibrary.add(listOf(shampoo),"Another name","123","QR_CODE") } }
 @Test fun rejectsEquivalentUpcDuplicate() {
  val existing=SavedBarcode(1,"Product","0123456789012","EAN_13")
  rejects { BarcodeLibrary.add(listOf(existing),"Same product","123456789012","UPC_A") }
 }
 @Test fun differentFormatsRemainSeparate() { assertEquals(2,BarcodeLibrary.add(listOf(shampoo),"Kitchen","123","CODE_128").size) }
 @Test fun selectCopiesCorrectValueAndPreservesSchedule() {
  val selected=BarcodeLibrary.select(alarm,1,listOf(shampoo))
  assertEquals(alarm.copy(code="123",format="QR_CODE",barcodeId=1),selected)
 }
 @Test fun noSelectionCannotSaveEvenWithOldCode() { rejects { BarcodeLibrary.select(alarm,0,listOf(shampoo)) } }
 @Test fun deletedSelectionCannotSave() { rejects { BarcodeLibrary.select(alarm,99,listOf(shampoo)) } }
 @Test fun ringingAlarmCannotChangeBarcode() {
  assertThrows(IllegalStateException::class.java) { BarcodeLibrary.select(alarm.copy(ringing=true),1,listOf(shampoo)) }
 }
 @Test fun renameChangesOnlyName() {
  val renamed=BarcodeLibrary.rename(listOf(shampoo),1,"New name").single()
  assertEquals(shampoo.copy(name="New name"),renamed)
  val selected=BarcodeLibrary.select(alarm,1,listOf(shampoo))
  assertEquals(selected,BarcodeLibrary.select(selected,1,listOf(renamed)))
 }
 @Test fun renameCanKeepOwnName() { assertEquals(listOf(shampoo),BarcodeLibrary.rename(listOf(shampoo),1,shampoo.name)) }
 @Test fun renameRejectsBlankAndMissingEntry() {
  rejects { BarcodeLibrary.rename(listOf(shampoo),1," ") }
  rejects { BarcodeLibrary.rename(listOf(shampoo),2,"Name") }
 }
 @Test fun usedBarcodeCannotBeDeletedEvenWhenAlarmDisabled() {
  rejects { BarcodeLibrary.delete(listOf(shampoo),1,listOf(alarm.copy(barcodeId=1,enabled=false))) }
 }
 @Test fun unusedBarcodeCanBeDeleted() { assertTrue(BarcodeLibrary.delete(listOf(shampoo),1,emptyList()).isEmpty()) }
 @Test fun migrationPreservesAlarmOccurrenceAndChallenge() {
  val old=alarm.copy(ringing=true,started=100,correct=7)
  val migrated=BarcodeLibrary.migrate(emptyList(),listOf(old))
  assertEquals(old.copy(barcodeId=migrated.barcodes.single().id),migrated.alarms.single())
  assertEquals("Saved barcode 1",migrated.barcodes.single().name)
 }
 @Test fun migrationDeduplicatesAndPreservesEquivalentPayloads() {
  val a=alarm.copy(code="0123456789012",format="EAN_13")
  val b=alarm.copy(id=6,code="123456789012",format="UPC_A")
  val m=BarcodeLibrary.migrate(emptyList(),listOf(a,b))
  assertEquals(1,m.barcodes.size)
  assertEquals(a.copy(barcodeId=1),m.alarms[0]);assertEquals(b.copy(barcodeId=1),m.alarms[1])
 }
 @Test fun migrationIsIdempotent() {
  val first=BarcodeLibrary.migrate(emptyList(),listOf(alarm))
  assertEquals(first,BarcodeLibrary.migrate(first.barcodes,first.alarms))
 }
 @Test fun migrationReusesExistingEntryAndAvoidsNameCollisions() {
  val existing=SavedBarcode(8,"Saved barcode 1","other","QR_CODE")
  val migrated=BarcodeLibrary.migrate(listOf(existing),listOf(alarm,alarm.copy(id=6,code="other")))
  assertEquals("Saved barcode 2",migrated.barcodes.last().name)
  assertEquals(9,migrated.alarms[0].barcodeId);assertEquals(8,migrated.alarms[1].barcodeId)
 }
 @Test fun independentAlarmsSelectDifferentEntries() {
  val other=shampoo.copy(id=2,name="Kitchen",code="456")
  val items=listOf(shampoo,other)
  assertEquals("123",BarcodeLibrary.select(alarm,1,items).code)
  assertEquals("456",BarcodeLibrary.select(alarm.copy(id=6),2,items).code)
 }
}
