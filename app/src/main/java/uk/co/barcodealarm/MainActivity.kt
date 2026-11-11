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
 private var currentPage=Page.HOME
 private var pendingCode: String?=null
 private var pendingFormat: String?=null
 private var pendingName=""
 private var nameInput: EditText?=null
 private var editing: Alarm?=null
 private var picker: TimePicker?=null
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  state?.getInt("editing",-1)?.takeIf { it>=0 }?.let { id -> editing=Store(this).get(id) ?: Alarm(id,7,0,"","") }
  if(state!=null && editing!=null) editing=editing!!.copy(hour=state.getInt("hour"),minute=state.getInt("minute"),code=state.getString("code", ""),format=state.getString("format", ""),sound=state.getString("sound", ""),barcodeId=state.getInt("barcodeId"))
  currentPage=Navigation.restorePage(state?.getString("page"),editing!=null)
  pendingCode=state?.getString("pendingCode");pendingFormat=state?.getString("pendingFormat")
  pendingName=state?.getString("pendingName").orEmpty()
  when(currentPage) { Page.BARCODES -> barcodes();Page.EDITOR -> editor();Page.SETTINGS -> settings();Page.HOME -> home() }
  if(pendingCode!=null) nameScannedBarcode()
 }
 override fun onSaveInstanceState(out: Bundle) {
  out.putString("page",currentPage.name)
  out.putString("pendingCode",pendingCode);out.putString("pendingFormat",pendingFormat)
  out.putString("pendingName",nameInput?.text?.toString() ?: pendingName)
  editing?.let { a -> out.putInt("editing",a.id);out.putInt("hour",picker?.hour ?: a.hour);out.putInt("minute",picker?.minute ?: a.minute);out.putString("code",a.code);out.putString("format",a.format);out.putString("sound",a.sound);out.putInt("barcodeId",a.barcodeId) };super.onSaveInstanceState(out)
 }
 override fun onResume() { super.onResume(); if(Store(this).all().any { it.ringing }) { startForegroundService(Intent(this,RingService::class.java));startActivity(Intent(this,RingActivity::class.java)) } else when(currentPage) { Page.SETTINGS -> settings();Page.HOME -> home();else -> Unit } }
 private fun home() {
  currentPage=Page.HOME
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
   caption(Store(this).barcodes().find { it.id==a.barcodeId }?.name ?: "No barcode selected")
   caption(if(a.enabled) "Enabled · scan to dismiss" else "Disabled · enable for the next occurrence")
   actionRow {
   button("Edit alarm") { editing=a;editor() }
   button("Delete",danger=true) { attempt { check(!a.ringing);Scheduling(this).cancel(a);Store(this).delete(a.id);home() } }
   }
  } }
  button("＋  Add alarm",primary=true) { editing=Alarm((Store(this).all().maxOfOrNull { it.id } ?: 0)+1,7,0,"","");editor() }
  button("Barcodes") { barcodes() }
  button("Test alarm") { test() };button("Settings & diagnostics") { settings() }
 }
 private fun captureTime() {
  val time=picker ?: return
  time.clearFocus()
  editing=editing?.copy(hour=time.hour,minute=time.minute)
 }
 private var discardDialog: AlertDialog?=null
 private fun navigateBack() {
  if(currentPage==Page.EDITOR) captureTime()
  val draft=editing
  val changed=draft?.let { Navigation.changed(it,Store(this).get(it.id)) } ?: false
  when(Navigation.back(currentPage,draft!=null,changed)) {
   BackDestination.HOME -> { editing=null;home() }
   BackDestination.EDITOR -> editor()
   BackDestination.CONFIRM_DISCARD -> {
    if(discardDialog?.isShowing==true) return
    discardDialog=AlertDialog.Builder(this).setTitle("Discard changes?")
     .setMessage("Your unsaved alarm changes will be lost.")
     .setNegativeButton("Keep editing",null)
     .setPositiveButton("Discard") { _,_ -> editing=null;home() }.show()
   }
   else -> Unit
  }
 }
 override fun onDestroy() {
  discardDialog?.dismiss()
  super.onDestroy()
 }
 private fun editor() {
  val a=editing ?: return
  currentPage=Page.EDITOR
  page("Set your alarm", "One time. Your chosen place to start.")
  setBackAction { navigateBack() }
  caption("WAKE-UP TIME")
  picker=TimePicker(this).apply { setIs24HourView(true);hour=a.hour;minute=a.minute };body.addView(picker)
  space(12)
  caption("DISMISSAL BARCODE")
  val library=Store(this).barcodes().sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
  if(library.isEmpty()) caption("Your library is empty. Add a barcode below before saving this alarm.")
  val choices=listOf("Select a barcode…")+library.map { it.name }
  body.addView(Spinner(this).apply {
   contentDescription="Dismissal barcode"
   minimumHeight=dp(56)
   adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_item,choices).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
   setSelection(library.indexOfFirst { it.id==a.barcodeId }.let { if(it<0) 0 else it+1 })
   onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
    override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: View?,position: Int,id: Long) {
     editing=editing?.copy(barcodeId=if(position==0) 0 else library[position-1].id)
    }
   }
  })
  button("Manage barcodes") { captureTime();barcodes() }
  button("Choose alarm sound") { captureTime();startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALARM).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false).putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,a.sound.takeIf { it.isNotEmpty() }?.let(Uri::parse)),42) }
  space(12)
  button("Save alarm",primary=true) { attempt {
   captureTime();val draft=editing!!;val updated=BarcodeLibrary.select(draft,draft.barcodeId,Store(this).barcodes())
   check(Store(this).get(updated.id)?.ringing != true) { "Cannot edit a ringing alarm" }
   if(updated.enabled) Scheduling(this).enable(updated) else Store(this).put(updated)
   editing=null;home()
  } }
  button("Cancel") { navigateBack() }
 }
 @Deprecated("Android result API") override fun onActivityResult(request: Int,result: Int,data: Intent?) {
  super.onActivityResult(request,result,data)
  val scan=IntentIntegrator.parseActivityResult(request,result,data)
  if(scan!=null) {
   if(scan.contents!=null) {
    pendingCode=scan.contents;pendingFormat=scan.formatName;pendingName=""
    barcodes();nameScannedBarcode()
   } else barcodes()
  }
  else if(request==42 && result==RESULT_OK) { val uri=data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);editing=editing?.copy(sound=uri?.toString().orEmpty());editor() }
 }
 private fun barcodes() {
  currentPage=Page.BARCODES;picker=null
  page("Barcodes", "Familiar names. One less thing to think about.")
  setBackAction { navigateBack() }
  val store=Store(this)
  val library=store.barcodes().sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
  if(library.isEmpty()) card { label("Your barcode library",22f);caption("Scan an everyday object or QR code and give it a name, such as Home bathroom shampoo.") }
  library.forEach { entry -> card {
   label(entry.name,22f);caption(entry.format.replace('_',' '))
   val uses=store.all().count { it.barcodeId==entry.id }
   caption(if(uses==1) "Used by 1 alarm" else "Used by $uses alarms")
   actionRow {
    button("Rename") { renameBarcode(entry) }
    button("Delete",danger=true) {
     if(uses>0) message("This barcode is used by an alarm. Change that alarm's barcode or delete the alarm first.")
     else AlertDialog.Builder(this).setTitle("Delete ${entry.name}?").setMessage("Remove this barcode from your library?")
      .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> attempt { store.deleteBarcode(entry.id);barcodes() } }.show()
    }
   }
  } }
  button("Scan new barcode",primary=true) { attempt { scan() } }
  button(if(editing!=null) "Back to alarm" else "Back to alarms") {
   navigateBack()
  }
 }
 private fun renameBarcode(entry: SavedBarcode) {
  val input=EditText(this).apply { setSingleLine(true);setText(entry.name);hint="Barcode name" }
  val dialog=AlertDialog.Builder(this).setTitle("Rename barcode").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
  dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt {
   Store(this).renameBarcode(entry.id,input.text.toString());dialog.dismiss();barcodes()
  } } }
  dialog.show()
 }
 private fun nameScannedBarcode() {
  val code=pendingCode ?: return;val format=pendingFormat ?: return
  val duplicate=Store(this).barcodes().find { Rules.matches(it.code,it.format,code,format) }
  if(duplicate!=null) {
   message("Already saved as ${duplicate.name}")
   editing=editing?.copy(barcodeId=duplicate.id)
   pendingCode=null;pendingFormat=null;return
  }
  val input=EditText(this).apply { setSingleLine(true);hint="e.g. Home bathroom shampoo";setText(pendingName) };nameInput=input
  fun clearDraft() { pendingCode=null;pendingFormat=null;pendingName="";nameInput=null }
  val dialog=AlertDialog.Builder(this).setTitle("Name your barcode").setView(input)
   .setNegativeButton("Cancel") { _,_ -> clearDraft() }.setPositiveButton("Save",null).create()
  dialog.setOnCancelListener { clearDraft() }
  dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt {
   val added=Store(this).addBarcode(input.text.toString(),code,format)
   editing=editing?.copy(barcodeId=added.id)
   clearDraft();dialog.dismiss();barcodes()
  } } }
  dialog.show()
 }
 private fun test() {
  val a=Store(this).all().firstOrNull { !it.ringing }
  if(a==null) { message("Save an alarm with a barcode first");return }
  AlertDialog.Builder(this).setMessage("Schedule ${a.hour}:${a.minute.toString().padStart(2,'0')} for 10 seconds from now? This replaces its scheduled occurrence and requires barcode or maths dismissal.").setPositiveButton("Test") { _,_ -> attempt { Scheduling(this).enable(a,System.currentTimeMillis()+10000);home() } }.setNegativeButton("Cancel",null).show()
 }
 private fun settings() {
  currentPage=Page.SETTINGS
  page("Settings", "A few essentials for dependable mornings.")
  setBackAction { navigateBack() }
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
  button("Test alarm") { test() };space(16);caption("LOCAL DIAGNOSTICS");card { label(Store(this).logs(),12f).typeface=Typeface.MONOSPACE };button("Back to alarms") { navigateBack() }
 }
}
