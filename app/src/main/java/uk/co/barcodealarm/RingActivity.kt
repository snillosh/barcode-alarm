package uk.co.barcodealarm

import android.content.Intent
import android.os.*
import android.view.WindowManager
import com.google.zxing.integration.android.IntentIntegrator

class RingActivity: Screen() {
 private var countdown: android.widget.TextView?=null
 private var maths=false
 private var question=Question.create()
 private val handler=Handler(Looper.getMainLooper())
 private val store by lazy { Store(this) }
 private fun active()=store.all().firstOrNull { it.ringing }
 private val tick=object: Runnable { override fun run() { if(active()==null) { finish();return }; if(maths) { val a=active()!!; if(Rules.ready(a,SystemClock.elapsedRealtime())) { dismiss(a) } else countdown?.text=remaining(a) };handler.postDelayed(this,500) } }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state);setShowWhenLocked(true);setTurnScreenOn(true);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  maths=state?.getBoolean("maths") ?: false;draw()
 }
 override fun onSaveInstanceState(out: Bundle) { out.putBoolean("maths",maths);super.onSaveInstanceState(out) }
 override fun onResume() { super.onResume();RingService.mathsVisible=maths;handler.post(tick);draw() }
 override fun onPause() { RingService.mathsVisible=false;handler.removeCallbacks(tick);super.onPause() }
 private fun remaining(a: Alarm): String {
  val ms=(300000-(SystemClock.elapsedRealtime()-a.started)).coerceAtLeast(0)
  return "Remaining: ${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"
 }
 private fun draw() {
  val a=active() ?: run { finish();return }
  page(if(maths) "Stay with it" else "Time to get up", if(maths) "Five minutes. Fifteen correct answers." else "Your morning starts with a small step.")
  label("%02d:%02d".format(a.hour,a.minute),64f).apply { typeface=android.graphics.Typeface.create("sans-serif-light",android.graphics.Typeface.NORMAL) }
  space(20)
  if(!maths) {
   button("Scan barcode",primary=true) { attempt { scan() } }
   button("Emergency maths challenge") {
    if(a.started<0) store.put(a.copy(started=SystemClock.elapsedRealtime()))
    maths=true;RingService.mathsVisible=true;draw()
   }
  } else {
   caption("CHALLENGE PROGRESS")
   label("${a.correct} / 15 correct",24f)
   countdown=label(remaining(a),22f)
   space(16)
   if(Rules.ready(a,SystemClock.elapsedRealtime())) { dismiss(a);return }
   if(a.correct<15) {
    label(question.text,40f)
    question.choices.chunked(2).forEach { choices -> actionRow { choices.forEach { answer -> button(answer.toString(),primary=true) {
     val current=active() ?: return@button
     if(answer==question.answer) store.put(current.copy(correct=current.correct+1)) else message("Incorrect")
     question=Question.create();draw()
    } } } }
   } else label("Questions complete. Keep this challenge open until the timer finishes.")
   button("Return to barcode scanner") { maths=false;RingService.mathsVisible=false;draw() }
  }
 }
 private fun dismiss(a: Alarm) {
  store.put(Rules.dismissed(a));store.log("Dismissed ${a.id}")
  // Stop immediately; restart for any other simultaneous alarm.
  stopService(Intent(this,RingService::class.java));RingService.mathsVisible=false
  if(active()!=null) { startForegroundService(Intent(this,RingService::class.java));maths=false;draw() }
  else { startActivity(Intent(this,MainActivity::class.java));finish() }
 }
 @Deprecated("Android result API") override fun onActivityResult(request: Int,result: Int,data: Intent?) {
  super.onActivityResult(request,result,data)
  val scan=IntentIntegrator.parseActivityResult(request,result,data) ?: return
  val a=active() ?: return
  if(scan.contents!=null) { if(Rules.matches(a.code,a.format,scan.contents,scan.formatName)) dismiss(a) else message("Incorrect barcode") }
 }
}
