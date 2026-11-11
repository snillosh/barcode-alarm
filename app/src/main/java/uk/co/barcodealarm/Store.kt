package uk.co.barcodealarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(private val c: Context) {
 private val p = c.getSharedPreferences("alarms", Context.MODE_PRIVATE)
 fun all(): List<Alarm> {
  migrateBarcodes();return readAlarms()
 }
 private fun readAlarms(): List<Alarm> {
  val array = JSONArray(p.getString("data", "[]"))
  return (0 until array.length()).map { val j=array.getJSONObject(it)
   Alarm(j.getInt("id"),j.getInt("hour"),j.getInt("minute"),j.getString("code"),j.getString("format"),j.optString("sound"),j.optBoolean("enabled"),j.optLong("due"),j.optBoolean("ringing"),j.optLong("started",-1),j.optInt("correct"),j.optInt("barcodeId")) }
 }
 fun get(id: Int) = all().find { it.id == id }
 fun put(a: Alarm) = write(all().filterNot { it.id==a.id } + a)
 fun delete(id: Int) = write(all().filterNot { it.id==id })
 private fun encodeAlarms(items: List<Alarm>): String {
  val arr=JSONArray()
  items.forEach { a -> arr.put(JSONObject().apply {
   put("id",a.id);put("hour",a.hour);put("minute",a.minute);put("code",a.code);put("format",a.format);put("sound",a.sound);put("enabled",a.enabled);put("due",a.due);put("ringing",a.ringing);put("started",a.started);put("correct",a.correct);put("barcodeId",a.barcodeId)
  }) }
  return arr.toString()
 }
 private fun write(items: List<Alarm>) {
  check(p.edit().putString("data",encodeAlarms(items)).commit()) { "Could not persist alarms" }
 }
 private fun readBarcodes(): List<SavedBarcode> {
  val array=JSONArray(p.getString("barcodes","[]"))
  return (0 until array.length()).map { index -> val j=array.getJSONObject(index)
   SavedBarcode(j.getInt("id"),j.getString("name"),j.getString("code"),j.getString("format"))
  }
 }
 private fun encodeBarcodes(items: List<SavedBarcode>): String {
  val array=JSONArray()
  items.forEach { a -> array.put(JSONObject().apply { put("id",a.id);put("name",a.name);put("code",a.code);put("format",a.format) }) }
  return array.toString()
 }
 private fun migrateBarcodes() {
  if(p.getBoolean("barcode_library_migrated",false)) return
  val migrated=BarcodeLibrary.migrate(readBarcodes(),readAlarms())
  check(p.edit().putString("data",encodeAlarms(migrated.alarms))
   .putString("barcodes",encodeBarcodes(migrated.barcodes)).putBoolean("barcode_library_migrated",true).commit()) { "Could not migrate barcode library" }
 }
 fun barcodes(): List<SavedBarcode> { migrateBarcodes();return readBarcodes() }
 private fun writeBarcodes(items: List<SavedBarcode>) {
  check(p.edit().putString("barcodes",encodeBarcodes(items)).commit()) { "Could not save barcode library" }
 }
 fun addBarcode(name: String, code: String, format: String): SavedBarcode {
  val updated=BarcodeLibrary.add(barcodes(),name,code,format);writeBarcodes(updated);return updated.last()
 }
 fun renameBarcode(id: Int, name: String) { writeBarcodes(BarcodeLibrary.rename(barcodes(),id,name)) }
 fun deleteBarcode(id: Int) { writeBarcodes(BarcodeLibrary.delete(barcodes(),id,all())) }
 fun log(message: String) {
  val text=p.getString("log", "").orEmpty()
  p.edit().putString("log", (text+"\n${java.time.Instant.now()} $message").takeLast(16000)).commit()
 }
 fun logs() = p.getString("log", "No events").orEmpty()
}
