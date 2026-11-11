package uk.co.barcodealarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(private val c: Context) {
 private val p = c.getSharedPreferences("alarms", Context.MODE_PRIVATE)
 fun all(): List<Alarm> {
  val array = JSONArray(p.getString("data", "[]"))
  return (0 until array.length()).map { val j=array.getJSONObject(it)
   Alarm(j.getInt("id"),j.getInt("hour"),j.getInt("minute"),j.getString("code"),j.getString("format"),j.optString("sound"),j.optBoolean("enabled"),j.optLong("due"),j.optBoolean("ringing"),j.optLong("started",-1),j.optInt("correct")) }
 }
 fun get(id: Int) = all().find { it.id == id }
 fun put(a: Alarm) = write(all().filterNot { it.id==a.id } + a)
 fun delete(id: Int) = write(all().filterNot { it.id==id })
 private fun write(items: List<Alarm>) {
  val arr=JSONArray()
  items.forEach { a -> arr.put(JSONObject().apply {
   put("id",a.id);put("hour",a.hour);put("minute",a.minute);put("code",a.code);put("format",a.format);put("sound",a.sound);put("enabled",a.enabled);put("due",a.due);put("ringing",a.ringing);put("started",a.started);put("correct",a.correct)
  }) }
  check(p.edit().putString("data",arr.toString()).commit()) { "Could not persist alarms" }
 }
 fun log(message: String) {
  val text=p.getString("log", "").orEmpty()
  p.edit().putString("log", (text+"\n${java.time.Instant.now()} $message").takeLast(16000)).commit()
 }
 fun logs() = p.getString("log", "No events").orEmpty()
}
