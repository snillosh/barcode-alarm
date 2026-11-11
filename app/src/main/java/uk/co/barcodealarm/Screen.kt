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
 protected val ink=Color.rgb(25,39,36)
 protected val muted=Color.rgb(91,108,101)
 protected val green=Color.rgb(36,91,69)
 protected val paper=Color.rgb(245,247,242)
 lateinit var body: LinearLayout
 fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
 fun shape(color: Int, radius: Int=22, border: Int?=null)=GradientDrawable().apply {
  setColor(color);cornerRadius=dp(radius).toFloat();border?.let { setStroke(dp(1),it) }
 }
 fun page(title: String, subtitle: String?=null) {
  window.statusBarColor=paper;window.navigationBarColor=paper
  window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
   background=shape(Color.WHITE,24,Color.rgb(224,231,222))
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
   setTextColor(if(primary) Color.WHITE else if(danger) Color.rgb(151,54,49) else green)
   background=RippleDrawable(ColorStateList.valueOf(Color.argb(35,80,110,90)),shape(if(primary) green else Color.rgb(234,240,231),16),null)
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
