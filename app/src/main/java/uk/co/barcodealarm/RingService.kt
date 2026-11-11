package uk.co.barcodealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Owns audio and vibration independently of the visible Activity. An Android foreground service has
 * an ongoing notification; it is not a foreground screen. AlarmManager triggers an alarm. This
 * service only maintains playback AFTER it has triggered.
 */
class RingService : Service() {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ring"
        private const val NOTIFICATION_ID = 1
        private const val PLAYBACK_UPDATE_INTERVAL_MILLIS = 250L

        // Similar to a C# static property. This controls only app playback volume, not progress.
        var isMathsVisible = false
    }

    private val store by lazy { AlarmStore(this) }
    private var mediaPlayer: MediaPlayer? = null
    private var playingAlarmId = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var vibrator: Vibrator
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val playbackUpdate =
        object : Runnable {
            override fun run() {
                updatePlayback()
            }
        }

    override fun onBind(intent: Intent?): IBinder? {
        // Activities start this service; they do not bind to it for remote method calls.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Vibrator::class.java)
        createNotificationChannel()
        val powerManager = getSystemService(PowerManager::class.java)
        val alarmWakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barcode:alarm")
        // Held for the ringing service lifetime, and explicitly released in onDestroy().
        alarmWakeLock.acquire()
        wakeLock = alarmWakeLock
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ringing alarms",
                NotificationManager.IMPORTANCE_HIGH,
            )
        // MediaPlayer supplies the looping sound; notification audio would duplicate it.
        channel.setSound(null, null)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createRingingNotification())
        updatePlayback()
        // Request service recreation after ordinary process termination. Force-stop still blocks
        // it.
        return START_STICKY
    }

    private fun createRingingNotification(): Notification {
        val openRingingScreen =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Barcode alarm ringing")
            .setContentText("Scan your barcode or complete maths")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openRingingScreen)
            .setFullScreenIntent(openRingingScreen, true)
            .build()
    }

    private fun updatePlayback() {
        mainThreadHandler.removeCallbacks(playbackUpdate)
        val alarm = store.getAlarms().firstOrNull { savedAlarm -> savedAlarm.isRinging }
        if (alarm == null) {
            stopSelf()
            return
        }
        if (playingAlarmId != alarm.id) {
            startAlarmPlayback(alarm)
        }
        // Set only this player's gain. Do not change Android's alarm stream or other apps' volume.
        val playbackVolume = if (isMathsVisible) 0.2f else 1f
        mediaPlayer?.setVolume(playbackVolume, playbackVolume)
        mainThreadHandler.postDelayed(playbackUpdate, PLAYBACK_UPDATE_INTERVAL_MILLIS)
    }

    private fun startAlarmPlayback(alarm: Alarm) {
        mediaPlayer?.release()
        mediaPlayer = null
        playingAlarmId = alarm.id
        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        requestAlarmAudioFocus(audioAttributes)
        playFirstAvailableSound(alarm, audioAttributes)
        val vibration = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        vibrator.vibrate(vibration, audioAttributes)
    }

    private fun requestAlarmAudioFocus(audioAttributes: AudioAttributes) {
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { /* Existing alarm playback continues until dismissal. */
                }
                .build()
        audioFocusRequest = request
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
    }

    private fun playFirstAvailableSound(alarm: Alarm, audioAttributes: AudioAttributes) {
        val selectedSound = if (alarm.soundUri.isNotBlank()) Uri.parse(alarm.soundUri) else null
        val candidates =
            listOfNotNull(
                selectedSound,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )
        for (soundUri in candidates) {
            try {
                val player = MediaPlayer()
                // Retain it before prepare(): a failed candidate must also be released.
                mediaPlayer = player
                player.setAudioAttributes(audioAttributes)
                player.setDataSource(this, soundUri)
                player.isLooping = true
                player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                player.prepare()
                player.start()
                return
            } catch (exception: Exception) {
                mediaPlayer?.release()
                mediaPlayer = null
                store.log("Playback failed $soundUri: $exception")
            }
        }
    }

    override fun onDestroy() {
        mainThreadHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        vibrator.cancel()
        val heldWakeLock = wakeLock
        if (heldWakeLock != null && heldWakeLock.isHeld) {
            heldWakeLock.release()
        }
        val focusRequest = audioFocusRequest
        if (focusRequest != null) {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focusRequest)
        }
        super.onDestroy()
    }
}
