package uk.co.barcodealarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import android.content.res.ColorStateList
import android.graphics.Typeface

class MainActivity: Screen() {
 private var showingSettings=false
 private var editing: Alarm?=null
 private var picker: TimePicker?=null
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  state?.getInt("editing",-1)?.takeIf { it>=0 }?.let { id -> editing=Store(this).get(id) ?: Alarm(id,7,0,"","") }
  if(state!=null && editing!=null) editing=editing!!.copy(hour=state.getInt("hour"),minute=state.getInt("minute"),code=state.getString("code", ""),format=state.getString("format", ""),sound=state.getString("sound", ""))
  showingSettings=state?.getBoolean("settings") ?: false
  if(editing!=null) editor() else if(showingSettings) settings() else home()
 }
 override fun onSaveInstanceState(out: Bundle) {
  out.putBoolean("settings",showingSettings)
  editing?.let { a -> out.putInt("editing",a.id);out.putInt("hour",picker?.hour ?: a.hour);out.putInt("minute",picker?.minute ?: a.minute);out.putString("code",a.code);out.putString("format",a.format);out.putString("sound",a.sound) };super.onSaveInstanceState(out)
 }
 override fun onResume() { super.onResume(); if(Store(this).all().any { it.ringing }) { startForegroundService(Intent(this,RingService::class.java));startActivity(Intent(this,RingActivity::class.java)) } else if(editing==null) { if(showingSettings) settings() else home() } }
 private fun home() {
  showingSettings=false
  page("Your alarms", "A deliberate start to your day.");picker=null
  if(Build.VERSION.SDK_INT>=31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
   Store(this).all().filter { it.enabled }.forEach { Store(this).put(it.copy(enabled=false,due=0)) }
   label("Exact alarm access is missing. Allow it in Settings, then enable your alarms.")
  }
  if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) label("Warning: alarm notifications are blocked. Open Settings.")
  val alarms=Store(this).all().sortedBy { it.hour*60+it.minute }
  if(alarms.isEmpty()) card {
   label("Make mornings intentional",22f)
   caption("Set a time. Register a barcode somewhere worth getting up for.")
  }
  alarms.forEach { a -> card {
   caption(if(a.enabled) "SCHEDULED • ONE TIME" else "READY WHEN YOU ARE")
   val row=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
   row.addView(TextView(this).apply {
    text="%02d:%02d".format(a.hour,a.minute);textSize=52f;setTextColor(ink)
    typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)
    layoutParams=LinearLayout.LayoutParams(0,-2,1f)
   })
   row.addView(Switch(this).apply {
    contentDescription="Enable alarm at %02d:%02d".format(a.hour,a.minute)
    isChecked=a.enabled;minimumWidth=dp(64);minimumHeight=dp(56)
    thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(green,muted))
    setOnCheckedChangeListener { _,checked -> attempt { if(checked) Scheduling(this@MainActivity).enable(a) else Scheduling(this@MainActivity).disable(a) };home() }
   })
   body.addView(row)
   caption(if(a.enabled) "Enabled · scan to dismiss" else "Disabled · enable for the next occurrence")
   actionRow {
   button("Edit alarm") { editing=a;editor() }
   button("Delete",danger=true) { attempt { check(!a.ringing);Scheduling(this).cancel(a);Store(this).delete(a.id);home() } }
   }
  } }
  button("＋  Add alarm",primary=true) { editing=Alarm((Store(this).all().maxOfOrNull { it.id } ?: 0)+1,7,0,"","");editor() }
  button("Test alarm") { test() };button("Settings & diagnostics") { settings() }
 }
 private fun captureTime() { editing=editing?.copy(hour=picker?.hour ?: 7,minute=picker?.minute ?: 0) }
 private fun editor() {
  val a=editing ?: return;page("Set your alarm", "One time. Your chosen place to start.")
  caption("WAKE-UP TIME")
  picker=TimePicker(this).apply { setIs24HourView(true);hour=a.hour;minute=a.minute };body.addView(picker)
  space(12)
  caption("DISMISSAL BARCODE")
  label(if(a.code.isEmpty()) "No barcode registered" else "✓  Barcode registered",20f)
  if(a.code.isNotEmpty()) caption(a.format.replace('_',' '))
  button(if(a.code.isEmpty()) "Scan a barcode" else "Replace barcode") { captureTime();attempt { scan() } }
  button("Choose alarm sound") { captureTime();startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALARM).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false).putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,a.sound.takeIf { it.isNotEmpty() }?.let(Uri::parse)),42) }
  space(12)
  button("Save alarm",primary=true) { attempt {
   captureTime();val updated=editing!!;check(updated.code.isNotEmpty()) { "Register a barcode first" }
   check(Store(this).get(updated.id)?.ringing != true) { "Cannot edit a ringing alarm" }
   if(updated.enabled) Scheduling(this).enable(updated) else Store(this).put(updated)
   editing=null;home()
  } }
  button("Cancel") { editing=null;home() }
 }
 @Deprecated("Android result API") override fun onActivityResult(request: Int,result: Int,data: Intent?) {
  super.onActivityResult(request,result,data)
  val scan=IntentIntegrator.parseActivityResult(request,result,data)
  if(scan!=null) { if(scan.contents!=null) editing=editing?.copy(code=scan.contents,format=scan.formatName);editor() }
  else if(request==42 && result==RESULT_OK) { val uri=data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);editing=editing?.copy(sound=uri?.toString().orEmpty());editor() }
 }
 private fun test() {
  val a=Store(this).all().firstOrNull { !it.ringing }
  if(a==null) { message("Save an alarm with a barcode first");return }
  AlertDialog.Builder(this).setMessage("Schedule ${a.hour}:${a.minute.toString().padStart(2,'0')} for 10 seconds from now? This replaces its scheduled occurrence and requires barcode or maths dismissal.").setPositiveButton("Test") { _,_ -> attempt { Scheduling(this).enable(a,System.currentTimeMillis()+10000);home() } }.setNegativeButton("Cancel",null).show()
 }
 private fun settings() {
  showingSettings=true
  page("Settings", "A few essentials for dependable mornings.")
  caption("APPEARANCE")
  card {
   body.addView(Switch(this).apply {
    text="Dark mode";textSize=18f;setTextColor(ink)
    minimumHeight=dp(56);isChecked=darkMode
    setOnCheckedChangeListener { _,enabled -> attempt {
     check(getSharedPreferences("appearance",MODE_PRIVATE).edit().putBoolean("dark_mode",enabled).commit()) { "Could not save appearance" }
     recreate()
    } }
   })
   caption("Use a darker palette across the app. Your choice is saved on this device.")
  }
  caption("PERMISSIONS & ACCESS")
  val exact=Build.VERSION.SDK_INT<31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
  card {
  label("Exact alarms  ·  ${if(exact) "Allowed" else "Needs access"}")
  button("Allow exact alarms") { if(Build.VERSION.SDK_INT>=31) attempt { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) } }
  }
  card {
  label("Notifications  ·  ${if(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "Allowed" else "Needs access"}")
  button("Allow notifications") { if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),4) else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
  }
  card {
  label("Lock-screen display  ·  ${if(Build.VERSION.SDK_INT<34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) "Allowed" else "Needs access"}")
  button("Full-screen alarm settings") { if(Build.VERSION.SDK_INT>=34) attempt { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName"))) } }
  }
  card {
  label("Camera  ·  ${if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) "Allowed" else "Needs access"}")
  button("Allow camera") { requestPermissions(arrayOf(Manifest.permission.CAMERA),5) }
  }
  caption("BATTERY & DEVICE")
  caption("In app settings, open Battery and select Unrestricted. After reboot, unlock once. Missed alarms are disabled; review diagnostics.")
  button("Battery / app settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
  button("Test alarm") { test() };space(16);caption("LOCAL DIAGNOSTICS");card { label(Store(this).logs(),12f).typeface=Typeface.MONOSPACE };button("Back to alarms") { home() }
 }
}
