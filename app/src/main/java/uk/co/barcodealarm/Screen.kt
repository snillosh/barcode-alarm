package uk.co.barcodealarm

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator

/** Native views only: shared spacing, typography and accessible touch targets. */
open class Screen: Activity() {
 protected val darkMode get()=getSharedPreferences("appearance",MODE_PRIVATE).getBoolean("dark_mode",false)
 protected val ink get()=getColor(if(darkMode) R.color.dark_ink else R.color.light_ink)
 protected val muted get()=getColor(if(darkMode) R.color.dark_muted else R.color.light_muted)
 protected val green get()=getColor(if(darkMode) R.color.dark_accent else R.color.light_accent)
 protected val paper get()=getColor(if(darkMode) R.color.dark_background else R.color.light_background)
 private val surface get()=getColor(if(darkMode) R.color.dark_surface else R.color.light_surface)
 private val border get()=getColor(if(darkMode) R.color.dark_border else R.color.light_border)
 private val secondary get()=getColor(if(darkMode) R.color.dark_secondary else R.color.light_secondary)
 private val onAccent get()=getColor(if(darkMode) R.color.dark_on_accent else R.color.light_on_accent)
 override fun onCreate(state: android.os.Bundle?) {
  setTheme(if(darkMode) R.style.AppTheme_Dark else R.style.AppTheme)
  super.onCreate(state)
 }
 // Register only on inner pages: Android retains normal root Back behaviour.
 private var backAction: (() -> Unit)?=null
 private var backCallback: android.window.OnBackInvokedCallback?=null
 protected fun setBackAction(action: (() -> Unit)?) {
  backAction=action
  if(Build.VERSION.SDK_INT>=33) {
   backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
   backCallback=null
   if(action!=null) {
    val callback=android.window.OnBackInvokedCallback { backAction?.invoke() }
    backCallback=callback
    onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
   }
  }
 }
 // This override serves API 28–32 only; API 33+ uses the dispatcher above.
 @android.annotation.SuppressLint("GestureBackNavigation")
 @Deprecated("Compatibility for Android 9–12; newer Android uses OnBackInvokedCallback")
 override fun onBackPressed() {
  val action=backAction
  if(action!=null) action() else super.onBackPressed()
 }
 override fun onDestroy() {
  setBackAction(null)
  super.onDestroy()
 }
 lateinit var body: LinearLayout
 fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
 fun shape(color: Int, radius: Int=22, border: Int?=null)=GradientDrawable().apply {
  setColor(color);cornerRadius=dp(radius).toFloat();border?.let { setStroke(dp(1),it) }
 }
 fun page(title: String, subtitle: String?=null) {
  setBackAction(null)
  window.statusBarColor=paper;window.navigationBarColor=paper
  window.decorView.systemUiVisibility=if(darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(28)) }
  val scroll=ScrollView(this).apply { setBackgroundColor(paper);isFillViewport=true;clipToPadding=false;addView(body) }
  scroll.setOnApplyWindowInsetsListener { view,insets ->
   if(Build.VERSION.SDK_INT>=30) {
    val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   } else view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
   insets
  }
  setContentView(scroll);scroll.requestApplyInsets()
  label("B A R C O D E   A L A R M",11f).apply { setTextColor(green);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
  label(title,34f).apply { typeface=Typeface.create("sans-serif",Typeface.BOLD);setPadding(0,dp(10),0,dp(4)) }
  subtitle?.let { caption(it) };space(18)
 }
 fun label(text: String,size: Float=18f)=TextView(this).also {
  it.text=text;it.textSize=size;it.setTextColor(ink);it.setLineSpacing(dp(3).toFloat(),1f)
  it.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) };body.addView(it)
 }
 fun caption(text: String)=label(text,14f).apply { setTextColor(muted) }
 fun space(height: Int) { body.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
 fun card(content: ()->Unit) {
  val parent=body
  val box=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(14))
   background=shape(surface,24,border)
   layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) }
  }
  parent.addView(box);body=box
  try { content() } finally { body=parent }
 }
 fun actionRow(content: ()->Unit) {
  val parent=body
  val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
  parent.addView(row,LinearLayout.LayoutParams(-1,-2));body=row
  try { content() } finally { body=parent }
 }
 fun button(text: String,primary: Boolean=false,danger: Boolean=false,action: ()->Unit) {
  body.addView(Button(this).apply {
   stateListAnimator=null;elevation=0f
   this.text=text;isAllCaps=false;textSize=16f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
   setTextColor(if(primary) onAccent else if(danger) (if(darkMode) Color.rgb(255,177,170) else Color.rgb(151,54,49)) else green)
   background=RippleDrawable(ColorStateList.valueOf(if(darkMode) Color.argb(45,200,240,210) else Color.argb(35,80,110,90)),shape(if(primary) green else secondary,16),null)
   minHeight=dp(54);minimumHeight=dp(54);gravity=Gravity.CENTER
   setPadding(dp(16),dp(12),dp(16),dp(12))
   layoutParams=(if(body.orientation==LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply {
    topMargin=dp(5);bottomMargin=dp(7)
    if(body.orientation==LinearLayout.HORIZONTAL) { marginStart=dp(3);marginEnd=dp(3) }
   }
   setOnClickListener { action() }
  })
 }
 fun message(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
 fun attempt(action: ()->Unit) { try { action() } catch(e: Exception) { Store(this).log(e.toString());message(e.message ?: "Action failed") } }
 fun scan() {
  IntentIntegrator(this).setCaptureActivity(PortraitCaptureActivity::class.java)
   .setDesiredBarcodeFormats(listOf("EAN_13","UPC_A","UPC_E","CODE_128","QR_CODE"))
   .setPrompt("Place your barcode inside the frame").setBeepEnabled(false).setOrientationLocked(true).initiateScan()
 }
}
