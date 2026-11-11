package uk.co.barcodealarm

/** A library code is immutable; renaming never changes an alarm's dismissal value. */
data class SavedBarcode(val id: Int, val name: String, val code: String, val format: String)
data class BarcodeMigration(val barcodes: List<SavedBarcode>, val alarms: List<Alarm>)

object BarcodeLibrary {
 private fun checkedName(name: String, items: List<SavedBarcode>, except: Int=0): String {
  val trimmed=name.trim()
  require(trimmed.isNotEmpty()) { "Give this barcode a name" }
  require(trimmed.length<=120) { "Use a name of at most 120 characters" }
  require(items.none { it.id!=except && it.name.equals(trimmed,ignoreCase=true) }) { "That name is already in use" }
  return trimmed
 }
 fun add(items: List<SavedBarcode>, name: String, code: String, format: String): List<SavedBarcode> {
  require(code.isNotEmpty() && format.isNotEmpty()) { "Scan a barcode first" }
  val duplicate=items.find { Rules.matches(it.code,it.format,code,format) }
  require(duplicate==null) { "Already saved as ${duplicate?.name}" }
  val entry=SavedBarcode((items.maxOfOrNull { it.id } ?: 0)+1,checkedName(name,items),code,format)
  return items+entry
 }
 fun rename(items: List<SavedBarcode>, id: Int, name: String): List<SavedBarcode> {
  require(items.any { it.id==id }) { "Barcode no longer exists" }
  val checked=checkedName(name,items,id)
  return items.map { if(it.id==id) it.copy(name=checked) else it }
 }
 fun delete(items: List<SavedBarcode>, id: Int, alarms: List<Alarm>): List<SavedBarcode> {
  require(alarms.none { it.barcodeId==id }) { "This barcode is used by an alarm. Choose another barcode for that alarm or delete the alarm first." }
  return items.filterNot { it.id==id }
 }
 fun select(a: Alarm, id: Int, items: List<SavedBarcode>): Alarm {
  check(!a.ringing) { "Cannot edit a ringing alarm" }
  val barcode=items.find { it.id==id } ?: throw IllegalArgumentException("Select a barcode from your library")
  return a.copy(barcodeId=id,code=barcode.code,format=barcode.format)
 }
 /** Link old alarms atomically, preserving every scheduled/ringing occurrence and its code. */
 fun migrate(items: List<SavedBarcode>, alarms: List<Alarm>): BarcodeMigration {
  var library=items
  val migrated=alarms.map { a ->
   if(a.barcodeId!=0 || a.code.isEmpty()) a else {
    var entry=library.find { Rules.matches(it.code,it.format,a.code,a.format) }
    if(entry==null) {
     var number=1
     while(library.any { it.name.equals("Saved barcode $number",ignoreCase=true) }) number++
     library=add(library,"Saved barcode $number",a.code,a.format)
     entry=library.last()
    }
    a.copy(barcodeId=entry.id)
   }
  }
  return BarcodeMigration(library,migrated)
 }
}
