package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.os.Build
import java.time.ZonedDateTime

class Scheduling(private val c: Context) {
 private val manager = c.getSystemService(AlarmManager::class.java)
 private val store = Store(c)
 private fun pending(a: Alarm) = PendingIntent.getBroadcast(c,a.id,Intent(c,AlarmReceiver::class.java).putExtra("id",a.id).putExtra("due",a.due),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 fun cancel(a: Alarm) { manager.cancel(pending(a)) }
 fun schedule(a: Alarm) {
  check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) { "Allow exact alarms in Settings first" }
  val show=PendingIntent.getActivity(c,a.id,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  manager.setAlarmClock(AlarmManager.AlarmClockInfo(a.due,show),pending(a))
  store.log("Scheduled ${a.id} at ${a.due}")
 }
 fun enable(a: Alarm, due: Long = Rules.next(a.hour,a.minute,ZonedDateTime.now())) {
  check(!a.ringing) { "Alarm is ringing" }
  cancel(a)
  val updated=a.copy(enabled=true,due=due,started=-1,correct=0)
  store.put(updated)
  try { schedule(updated) } catch(e: Exception) {
   store.put(updated.copy(enabled=false,due=0)); store.log("Schedule failed: $e"); throw e
  }
 }
 fun disable(a: Alarm) { check(!a.ringing); cancel(a);store.put(Rules.dismissed(a)) }
}
class AlarmReceiver: BroadcastReceiver() {
 override fun onReceive(c: Context,i: Intent) {
  val s=Store(c);val a=s.get(i.getIntExtra("id",-1)) ?: return
  if (!Rules.shouldTrigger(a,i.getLongExtra("due",-1))) return
  s.put(a.copy(enabled=false,due=0,ringing=true));s.log("Triggered ${a.id}")
  try { c.startForegroundService(Intent(c,RingService::class.java)) } catch(e: Exception) { s.log("Service start failed: $e") }
 }
}
class RestoreReceiver: BroadcastReceiver() {
 override fun onReceive(c: Context,i: Intent) {
  val s=Store(c); val boot=i.action==Intent.ACTION_BOOT_COMPLETED
  s.all().forEach { a ->
   if(a.ringing && boot) s.put(a.copy(started=-1,correct=0))
   if(a.enabled) {
    if(a.due<=System.currentTimeMillis()) { s.put(a.copy(enabled=false,due=0));s.log("Missed ${a.id}; re-enable manually") }
    else try { Scheduling(c).schedule(a) } catch(e: Exception) { s.put(a.copy(enabled=false,due=0));s.log("Restore failed: $e") }
   }
  }
  // Never launch media playback from BOOT_COMPLETED. Resume unresolved ringing on app open.
 }
}
