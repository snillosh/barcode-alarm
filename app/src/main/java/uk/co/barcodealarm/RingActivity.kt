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
    private val store by lazy { ActiveAlarmStore(this) }

    private fun getActiveAlarm(): Alarm? {
        val requested = intent.getIntExtra("occurrence", -1)
        return if (requested != -1) store.getAlarms().find { it.id == requested }
        else
            store.getAlarms().firstOrNull { OccurrenceStore(this).get(it.id)?.audible == true }
                ?: store.getAlarms().firstOrNull()
    }

    private val countdownUpdate =
        object : Runnable {
            override fun run() {
                val alarm = getActiveAlarm()
                if (alarm == null) {
                    showAlarmPage()
                    return
                }
                if (!isMathsVisible) stageCountdown?.text = stageText(alarm.id)
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

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        isMathsVisible = false
        RingService.isMathsVisible = false
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
            val verification = OccurrenceStore(this).get(intent.getIntExtra("occurrence", -1))
            if (
                verification != null &&
                    verification.stage in
                        setOf(OccurrenceStage.VERIFY_DELAY, OccurrenceStage.VERIFY_RESPONSE)
            ) {
                showPage("Are you still awake?")
                addCaption(AlarmOverview.displayLabel(verification.snapshot))
                addButton("I'm Awake", primary = true) {
                    runUiAction {
                        OccurrenceManager(this).event(verification.id, "awake", 0)
                        finish()
                    }
                }
            } else finish()
            return
        }
        val isTimer = OccurrenceStore(this).get(alarm.id)?.source == OccurrenceSource.TIMER
        val title =
            if (isMathsVisible) "Stay with it"
            else if (isTimer) "Timer finished" else "Time to get up"
        val subtitle =
            if (isMathsVisible) "Five minutes. Fifteen correct answers."
            else "Your morning starts with a small step."
        showPage(title, subtitle)
        addText(AlarmOverview.displayLabel(alarm), 24f)
        if (Navigation.ringingBack(isMathsVisible) == BackDestination.RINGING) {
            setBackAction { returnToRinging() }
        }
        val timeHeading =
            addText(
                if (isTimer) "Time is up" else "%02d:%02d".format(alarm.hour, alarm.minute),
                if (isTimer) 36f else 64f,
            )
        timeHeading.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        addSpace(20)
        if (isMathsVisible) addMathsChallenge(alarm) else addBarcodeDismissal(alarm)
    }

    private var stageCountdown: TextView? = null

    private fun stageText(id: Int): String {
        val occurrence = OccurrenceStore(this).get(id) ?: return ""
        if (occurrence.dismissalStage == OccurrenceStage.SECOND_RINGING)
            return "Completion window missed. Scan the second barcode to stop the alarm."
        val target =
            if (occurrence.stage == OccurrenceStage.SECOND_WAIT) occurrence.eligibleAt
            else occurrence.deadlineAt
        val remaining =
            if (occurrence.stage == OccurrenceStage.SECOND_WAIT && occurrence.eligibleElapsed > 0)
                occurrence.eligibleElapsed - SystemClock.elapsedRealtime()
            else target - System.currentTimeMillis()
        val seconds = remaining.coerceAtLeast(0) / 1000
        return if (occurrence.stage == OccurrenceStage.SECOND_WAIT)
            "Alarm silenced. Ready in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        else
            "Second barcode ready. Time before restart: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun addBarcodeDismissal(alarm: Alarm) {
        val occurrence = OccurrenceStore(this).get(alarm.id) ?: return
        val second =
            occurrence.dismissalStage in
                setOf(
                    OccurrenceStage.SECOND_WAIT,
                    OccurrenceStage.SECOND_READY,
                    OccurrenceStage.SECOND_RINGING,
                )
        val barcodeId = if (second) alarm.secondBarcodeId else alarm.barcodeId
        val barcode = store.getBarcodes().find { it.id == barcodeId }
        if (occurrence.stage == OccurrenceStage.SNOOZED)
            addCaption("Snoozed · You can dismiss now without waiting or restarting the sound.")
        if (second) stageCountdown = addCaption(stageText(alarm.id))
        val name = if (second) alarm.secondBarcodeName else alarm.firstBarcodeName
        if (name.isNotEmpty()) addCaption("Scan $name")
        else if (barcode != null) addCaption("Scan ${barcode.name}")
        if (alarm.barcodeValue.isEmpty()) {
            addButton("Dismiss", primary = true) {
                runUiAction {
                    OccurrenceManager(this).dismiss(alarm.id, normal = true)
                    finishCurrentOccurrence()
                }
            }
        } else addButton("Scan barcode", primary = true) { runUiAction { openBarcodeScanner() } }
        if (occurrence.canSnooze)
            addButton("Snooze · ${AlarmRules.SNOOZE_MINUTES} minutes") {
                runUiAction { snoozeAlarm(alarm) }
            }
        if (alarm.barcodeValue.isNotEmpty())
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

    private fun snoozeAlarm(alarm: Alarm) {
        // Scheduling must succeed before stopping the player or closing the ringing screen.
        OccurrenceManager(this).snooze(alarm.id)
        finishCurrentOccurrence()
    }

    private fun dismissAlarm(alarm: Alarm) {
        OccurrenceManager(this).dismiss(alarm.id)
        store.log("Dismissed occurrence ${alarm.id}")
        finishCurrentOccurrence()
    }

    private fun finishCurrentOccurrence() {
        stopService(Intent(this, RingService::class.java))
        RingService.isMathsVisible = false
        val returnToTimers =
            OccurrenceStore(this).get(intent.getIntExtra("occurrence", -1))?.source ==
                OccurrenceSource.TIMER
        val candidates = store.getAlarms()
        val remaining =
            candidates.firstOrNull { OccurrenceStore(this).get(it.id)?.audible == true }
                ?: candidates.firstOrNull {
                    OccurrenceStore(this).get(it.id)?.stage != OccurrenceStage.SNOOZED
                }
        if (remaining != null) {
            intent = Intent(this, RingActivity::class.java).putExtra("occurrence", remaining.id)
            // Simultaneous occurrences each require their own authorised dismissal.
            if (OccurrenceStore(this).all().any { it.audible })
                startForegroundService(Intent(this, RingService::class.java))
            isMathsVisible = false
            showAlarmPage()
        } else {
            startActivity(
                Intent(
                    this,
                    if (returnToTimers) TimerActivity::class.java else MainActivity::class.java,
                )
            )
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
        runUiAction {
            OccurrenceManager(this).scan(alarm.id, scannedValue, scanResult.formatName)
            finishCurrentOccurrence()
        }
    }
}
