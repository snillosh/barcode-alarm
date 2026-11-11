package uk.co.barcodealarm

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*

class RingService: Service() {
 private var player: MediaPlayer?=null
 private var playing=-1
 private var wake: PowerManager.WakeLock?=null
 private lateinit var vibrator: Vibrator
 private val handler=Handler(Looper.getMainLooper())
 private var focus: AudioFocusRequest?=null
 companion object { var mathsVisible=false }
 override fun onBind(i: Intent?) = null
 override fun onCreate() {
  super.onCreate()
  vibrator=getSystemService(Vibrator::class.java)
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("ring","Ringing alarms",NotificationManager.IMPORTANCE_HIGH).apply { setSound(null,null); lockscreenVisibility=Notification.VISIBILITY_PUBLIC })
  wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"barcode:alarm").apply { acquire() }
 }
 override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
  val pi=PendingIntent.getActivity(this,0,Intent(this,RingActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  startForeground(1,Notification.Builder(this,"ring").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Barcode alarm ringing").setContentText("Scan your barcode or complete maths").setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(pi).setFullScreenIntent(pi,true).build())
  update(); return START_STICKY
 }
 private val tick=object: Runnable { override fun run() { update() } }
 private fun update() {
  handler.removeCallbacks(tick)
  val a=Store(this).all().firstOrNull { it.ringing }
  if(a==null) { stopSelf();return }
  if(playing!=a.id) {
   player?.release();player=null;playing=a.id
   val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
   focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
   getSystemService(AudioManager::class.java).requestAudioFocus(focus!!)
   val uris=listOfNotNull(a.sound.takeIf { it.isNotBlank() }?.let(Uri::parse),RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
   for(uri in uris) {
    try { player=MediaPlayer();player!!.apply { setAudioAttributes(attrs);setDataSource(this@RingService,uri);isLooping=true;setWakeMode(this@RingService,PowerManager.PARTIAL_WAKE_LOCK);prepare();start() };break }
    catch(e: Exception) { player?.release();player=null;Store(this).log("Playback failed $uri: $e") }
   }
   vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,700,500),0),attrs)
  }
  val volume=if(mathsVisible) 0.2f else 1f
  player?.setVolume(volume,volume)
  handler.postDelayed(tick,250)
 }
 override fun onDestroy() {
  handler.removeCallbacksAndMessages(null);player?.release();vibrator.cancel();wake?.let { if(it.isHeld) it.release() }
  focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) };super.onDestroy()
 }
}
