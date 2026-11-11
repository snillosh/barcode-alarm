package uk.co.barcodealarm

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator

/** Displays dismissal options; RingService owns playback even when this Activity is paused. */
class RingActivity : BaseActivity() {
    companion object {
        private const val COUNTDOWN_REFRESH_INTERVAL_MILLIS = 500L
    }

    private var countdownText: TextView? = null
    private var isMathsVisible = false
    private var currentQuestion = MathsQuestion.create()
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val store by lazy { AlarmStore(this) }

    private fun getActiveAlarm(): Alarm? {
        return store.getAlarms().firstOrNull { alarm -> alarm.isRinging }
    }

    private val countdownUpdate =
        object : Runnable {
            override fun run() {
                val alarm = getActiveAlarm()
                if (alarm == null) {
                    finish()
                    return
                }
                if (isMathsVisible) {
                    if (AlarmRules.canDismissWithMaths(alarm, SystemClock.elapsedRealtime())) {
                        dismissAlarm(alarm)
                    } else {
                        countdownText?.text = formatRemainingTime(alarm)
                    }
                }
                mainThreadHandler.postDelayed(this, COUNTDOWN_REFRESH_INTERVAL_MILLIS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isMathsVisible = savedInstanceState?.getBoolean("maths") ?: false
        showAlarmPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // The page is temporary UI state. Challenge start/count are already persisted in
        // AlarmStore.
        outState.putBoolean("maths", isMathsVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        RingService.isMathsVisible = isMathsVisible
        mainThreadHandler.post(countdownUpdate)
        showAlarmPage()
    }

    override fun onPause() {
        // Backgrounding or opening the scanner restores normal playback without resetting progress.
        RingService.isMathsVisible = false
        mainThreadHandler.removeCallbacks(countdownUpdate)
        super.onPause()
    }

    private fun returnToRinging() {
        isMathsVisible = false
        RingService.isMathsVisible = false
        showAlarmPage()
    }

    private fun formatRemainingTime(alarm: Alarm): String {
        val elapsedMillis = SystemClock.elapsedRealtime() - alarm.mathsStartedAtElapsedMillis
        val remainingMillis = (AlarmRules.MATHS_DURATION_MILLIS - elapsedMillis).coerceAtLeast(0)
        val minutes = remainingMillis / 60_000
        val seconds = ((remainingMillis / 1_000) % 60).toString().padStart(2, '0')
        return "Remaining: $minutes:$seconds"
    }

    private fun showAlarmPage() {
        val alarm = getActiveAlarm()
        if (alarm == null) {
            finish()
            return
        }
        val title = if (isMathsVisible) "Stay with it" else "Time to get up"
        val subtitle =
            if (isMathsVisible) "Five minutes. Fifteen correct answers."
            else "Your morning starts with a small step."
        showPage(title, subtitle)
        if (Navigation.ringingBack(isMathsVisible) == BackDestination.RINGING) {
            setBackAction { returnToRinging() }
        }
        val timeHeading = addText("%02d:%02d".format(alarm.hour, alarm.minute), 64f)
        timeHeading.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        addSpace(20)
        if (isMathsVisible) addMathsChallenge(alarm) else addBarcodeDismissal(alarm)
    }

    private fun addBarcodeDismissal(alarm: Alarm) {
        val barcode =
            store.getBarcodes().find { savedBarcode -> savedBarcode.id == alarm.barcodeId }
        if (barcode != null) addCaption("Scan ${barcode.name}")
        addButton("Scan barcode", primary = true) { runUiAction { openBarcodeScanner() } }
        addButton("Emergency maths challenge") { startMathsChallenge(alarm) }
    }

    private fun startMathsChallenge(alarm: Alarm) {
        if (alarm.mathsStartedAtElapsedMillis < 0) {
            // A monotonic clock cannot be advanced by changing the device's date/time.
            store.saveAlarm(alarm.copy(mathsStartedAtElapsedMillis = SystemClock.elapsedRealtime()))
        }
        isMathsVisible = true
        RingService.isMathsVisible = true
        showAlarmPage()
    }

    private fun addMathsChallenge(alarm: Alarm) {
        addCaption("CHALLENGE PROGRESS")
        addText("${alarm.correctAnswerCount} / 15 correct", 24f)
        countdownText = addText(formatRemainingTime(alarm), 22f)
        addSpace(16)
        if (AlarmRules.canDismissWithMaths(alarm, SystemClock.elapsedRealtime())) {
            dismissAlarm(alarm)
            return
        }
        if (alarm.correctAnswerCount < AlarmRules.REQUIRED_CORRECT_ANSWERS) {
            addText(currentQuestion.text, 40f)
            // chunked(2) is similar to LINQ Chunk(2): two answer buttons per row.
            for (row in currentQuestion.choices.chunked(2)) {
                addButtonRow {
                    for (answer in row) {
                        addButton(answer.toString(), primary = true) { answerQuestion(answer) }
                    }
                }
            }
        } else {
            addText("Questions complete. Keep this challenge open until the timer finishes.")
        }
        addButton("Return to barcode scanner") { returnToRinging() }
    }

    private fun answerQuestion(answer: Int) {
        val alarm = getActiveAlarm() ?: return
        if (answer == currentQuestion.answer) {
            store.saveAlarm(alarm.copy(correctAnswerCount = alarm.correctAnswerCount + 1))
        } else {
            showMessage("Incorrect")
        }
        currentQuestion = MathsQuestion.create()
        showAlarmPage()
    }

    private fun dismissAlarm(alarm: Alarm) {
        store.saveAlarm(AlarmRules.markDismissed(alarm))
        store.log("Dismissed ${alarm.id}")
        stopService(Intent(this, RingService::class.java))
        RingService.isMathsVisible = false
        if (getActiveAlarm() != null) {
            // Simultaneous occurrences each require their own authorised dismissal.
            startForegroundService(Intent(this, RingService::class.java))
            isMathsVisible = false
            showAlarmPage()
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    @Deprecated("Android result API retained for the existing native Activity/scanner integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult =
            IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val alarm = getActiveAlarm() ?: return
        val scannedValue = scanResult.contents ?: return
        val matches =
            AlarmRules.barcodesMatch(
                alarm.barcodeValue,
                alarm.barcodeFormat,
                scannedValue,
                scanResult.formatName,
            )
        if (matches) dismissAlarm(alarm) else showMessage("Incorrect barcode")
    }
}
