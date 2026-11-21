package uk.co.barcodealarm

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

/** Preset/list UI only. Exact expiration, audio, scanning and maths belong to the shared engine. */
class TimerActivity : BaseActivity() {
    private val presets by lazy { TimerStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var draft: TimerPreset? = null
    private var labelInput: EditText? = null
    private var hoursInput: EditText? = null
    private var minutesInput: EditText? = null
    private var secondsInput: EditText? = null
    private var reminderInput: EditText? = null
    private var hours = "0"
    private var minutes = "15"
    private var seconds = "0"
    private var reminder = "5"
    private val countdownViews = mutableMapOf<Int, TextView>()
    private var renderedStates = emptyList<Triple<Int, OccurrenceStage, Boolean>>()

    private fun runStates(runs: List<Occurrence>) =
        runs.map {
            Triple(
                it.id,
                it.stage,
                TimerRules.canCancel(it, System.currentTimeMillis(), SystemClock.elapsedRealtime()),
            )
        }

    private val refresh =
        object : Runnable {
            override fun run() {
                if (!visible || draft != null) return
                val runs = timerOccurrences()
                if (runStates(runs) != renderedStates) {
                    showTimers()
                    return
                }
                for (run in runs.filter { it.stage == OccurrenceStage.COUNTDOWN }) {
                    countdownViews[run.id]?.text =
                        "${TimerRules.countdown(TimerRules.remaining(run, System.currentTimeMillis(), SystemClock.elapsedRealtime()))} remaining"
                }
                handler.postDelayed(this, 1000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = savedInstanceState
        state?.getString("timerDraft")?.let { draft = TimerJsonCodec.decode(it).single() }
        hours = state?.getString("hours") ?: hours
        minutes = state?.getString("minutes") ?: minutes
        seconds = state?.getString("seconds") ?: seconds
        reminder = state?.getString("reminder") ?: reminder
        runUiAction { OccurrenceManager(this).restore(false) }
        if (draft == null) showTimers() else showEditor()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (OccurrenceStore(this).all().any { it.audible })
            startForegroundService(Intent(this, RingService::class.java))
        if (draft == null) showTimers() else showEditor()
    }

    override fun onPause() {
        captureInputs()
        visible = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(state: Bundle) {
        captureInputs()
        draft?.let { state.putString("timerDraft", TimerJsonCodec.encode(listOf(it))) }
        state.putString("hours", hours)
        state.putString("minutes", minutes)
        state.putString("seconds", seconds)
        state.putString("reminder", reminder)
        super.onSaveInstanceState(state)
    }

    private fun timerOccurrences() =
        OccurrenceStore(this).all().filter { it.source == OccurrenceSource.TIMER }

    private fun showTimers() {
        handler.removeCallbacks(refresh)
        labelInput = null
        hoursInput = null
        minutesInput = null
        secondsInput = null
        reminderInput = null
        countdownViews.clear()
        showPage("Your timers", "Save a countdown. Start it whenever you need it.")
        addTopLevelTabs(timersSelected = true)
        val runs = timerOccurrences()
        if (
            Build.VERSION.SDK_INT >= 31 &&
                !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            addCaption(
                "Exact alarm access is missing. Timer delivery is blocked. Open Alarms → Settings to allow exact alarms."
            )
        }
        renderedStates = runStates(runs)
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            addCaption(
                "Notifications are blocked. Interval chimes need notification permission; check Alarms → Settings."
            )
        }
        for (preset in presets.all()) addPresetCard(
            preset,
            runs.find { it.definitionId == preset.id },
        )
        if (presets.all().isEmpty()) addCaption("No saved timers yet.")
        addButton("＋  Add timer", primary = true) {
            runUiAction {
                draft = TimerPreset(presets.nextId(), 15 * 60_000L)
                hours = "0"
                minutes = "15"
                seconds = "0"
                reminder = "5"
                showEditor()
            }
        }
        if (visible) handler.postDelayed(refresh, 1000)
    }

    private fun addPresetCard(preset: TimerPreset, run: Occurrence?) {
        addCard {
            addText(TimerRules.label(preset), 24f)
            addCaption("Duration · ${TimerRules.countdown(preset.durationMillis)}")
            val codes = AlarmStore(this).getBarcodes()
            val config = preset.configuration
            val first =
                codes.find { it.id == config.barcodeId }?.name
                    ?: config.firstBarcodeName.ifEmpty { "Barcode" }
            val second =
                codes.find { it.id == config.secondBarcodeId }?.name
                    ?: config.secondBarcodeName.ifEmpty { "Second barcode" }
            addCaption(
                when {
                    config.barcodeValue.isEmpty() -> "Dismiss · Normal button"
                    config.secondBarcodeValue.isEmpty() -> "Barcode · $first"
                    else ->
                        "Barcodes · $first → $second · ${config.scanIntervalMinutes} min between scans"
                }
            )
            addCaption(
                if (preset.reminderMinutes > 0)
                    "Reminders · Every ${preset.reminderMinutes} minutes"
                else "Reminders · Off"
            )
            addCaption(if (preset.configuration.canBeSnoozed) "Snooze · On" else "Snooze · Off")
            when (run?.stage) {
                OccurrenceStage.COUNTDOWN -> {
                    if (run.snapshot.label != TimerRules.label(preset))
                        addCaption("Running · ${run.snapshot.label}")
                    countdownViews[run.id] =
                        addText(
                            "${TimerRules.countdown(TimerRules.remaining(run, System.currentTimeMillis(), SystemClock.elapsedRealtime()))} remaining",
                            28f,
                        )
                    if (
                        !TimerRules.canCancel(
                            run,
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(),
                        )
                    ) {
                        addCaption(
                            if (
                                TimerRules.remaining(
                                    run,
                                    System.currentTimeMillis(),
                                    SystemClock.elapsedRealtime(),
                                ) == 0L
                            )
                                "Time is up · awaiting dismissal"
                            else
                                "Final ${run.timerCancellationLockMinutes} minute(s) protected · cancellation and deletion unavailable"
                        )
                    } else
                        addButton("Cancel countdown") {
                            runUiAction {
                                OccurrenceManager(this).cancelTimer(run.id)
                                showTimers()
                            }
                        }
                }
                null,
                OccurrenceStage.COMPLETED,
                OccurrenceStage.CANCELLED -> {
                    addCaption(
                        when (run?.stage) {
                            OccurrenceStage.COMPLETED -> "COMPLETED · READY AGAIN"
                            OccurrenceStage.CANCELLED -> "CANCELLED · READY AGAIN"
                            else -> "READY"
                        }
                    )
                    addButton("Start", primary = true) {
                        runUiAction {
                            val current =
                                presets.all().find { it.id == preset.id }
                                    ?: error("Timer no longer exists")
                            OccurrenceManager(this).startTimer(current)
                            showTimers()
                        }
                    }
                }
                else -> {
                    addCaption(
                        when (run.stage) {
                            OccurrenceStage.SECOND_WAIT -> "SILENCED · WAITING FOR SECOND BARCODE"
                            OccurrenceStage.SECOND_READY -> "SECOND BARCODE READY"
                            OccurrenceStage.SNOOZED -> "SNOOZED"
                            else -> "RINGING · DISMISSAL REQUIRED"
                        }
                    )
                    addButton(
                        if (run.stage == OccurrenceStage.SNOOZED) "Dismiss snoozed timer"
                        else "Open timer",
                        primary = true,
                    ) {
                        startActivity(
                            Intent(this, RingActivity::class.java).putExtra("occurrence", run.id)
                        )
                    }
                }
            }
            addButtonRow {
                addButton("Edit") {
                    draft = preset
                    hours = (preset.durationMillis / 3_600_000).toString()
                    minutes = (preset.durationMillis / 60_000 % 60).toString()
                    seconds = (preset.durationMillis / 1000 % 60).toString()
                    reminder = preset.reminderMinutes.takeIf { it > 0 }?.toString() ?: "5"
                    showEditor()
                }
                addButton("Delete", danger = true) {
                    AlertDialog.Builder(this@TimerActivity)
                        .setTitle("Delete ${TimerRules.label(preset)}?")
                        .setMessage(
                            "An unprotected countdown will be cancelled. Protected or expired timers must be dismissed first."
                        )
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Delete") { _, _ ->
                            runUiAction {
                                OccurrenceManager(this@TimerActivity).deleteTimerPreset(preset.id)
                                showTimers()
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun captureInputs() {
        draft =
            draft?.let {
                it.copy(
                    configuration =
                        it.configuration.copy(
                            label = labelInput?.text?.toString() ?: it.configuration.label
                        )
                )
            }
        hours = hoursInput?.text?.toString() ?: hours
        minutes = minutesInput?.text?.toString() ?: minutes
        seconds = secondsInput?.text?.toString() ?: seconds
        reminder = reminderInput?.text?.toString() ?: reminder
    }

    private fun numberInput(title: String, value: String): EditText {
        addCaption(title)
        val input = EditText(this)
        input.contentDescription = title
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setSingleLine(true)
        input.filters = arrayOf(InputFilter.LengthFilter(5))
        input.setTextColor(textColor)
        input.setText(value)
        contentLayout.addView(input)
        return input
    }

    private fun showEditor() {
        handler.removeCallbacks(refresh)
        val preset = draft ?: return
        showPage("Set your timer", "Reusable countdown. Your chosen dismissal.")
        setBackAction { leaveEditor() }
        addCaption("Timer label")
        labelInput =
            EditText(this).apply {
                contentDescription = "Timer label"
                hint = "Timer"
                setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(AlarmOverview.MAX_LABEL_LENGTH))
                setTextColor(textColor)
                setText(preset.configuration.label)
            }
        contentLayout.addView(labelInput)
        hoursInput = numberInput("Hours (0–168)", hours)
        minutesInput = numberInput("Minutes (0–59)", minutes)
        secondsInput = numberInput("Seconds (0–59)", seconds)
        val snooze =
            CheckBox(this).apply {
                text = "Can be snoozed"
                setTextColor(textColor)
                minimumHeight = toPixels(56)
                isChecked = preset.configuration.canBeSnoozed
                setOnCheckedChangeListener { _, checked ->
                    draft =
                        draft?.let {
                            it.copy(configuration = it.configuration.copy(canBeSnoozed = checked))
                        }
                }
            }
        contentLayout.addView(snooze)
        addBarcodeSettings(preset.configuration)
        val enabled =
            CheckBox(this).apply {
                text = "Enable interval reminders"
                setTextColor(textColor)
                isChecked = preset.reminderMinutes > 0
                setOnCheckedChangeListener { _, checked ->
                    captureInputs()
                    draft =
                        draft?.copy(
                            reminderMinutes =
                                if (checked) (reminder.toIntOrNull()?.coerceIn(1, 10080) ?: 5)
                                else 0
                        )
                    showEditor()
                }
            }
        contentLayout.addView(enabled)
        reminderInput =
            if (preset.reminderMinutes > 0) numberInput("Remind me every N minutes", reminder)
            else null
        addCaption(
            "Chimes stop at expiration. Reminders use the notification channel's sound settings."
        )
        addButton("Choose timer sound") {
            captureInputs()
            val sound = draft?.configuration?.soundUri.orEmpty()
            startActivityForResult(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        sound.takeIf { it.isNotEmpty() }?.let(Uri::parse),
                    ),
                42,
            )
        }
        addButton("Manage barcodes") {
            captureInputs()
            startActivity(Intent(this, MainActivity::class.java).putExtra("openBarcodes", true))
        }
        addCaption(
            "Sound, vibration, maths and gradual volume use the alarm engine's existing settings. Changes apply to the next run."
        )
        addButton("Save timer", primary = true) { runUiAction { savePreset() } }
        addButton("Cancel") { leaveEditor() }
    }

    private fun dropdown(
        title: String,
        names: List<String>,
        selected: Int,
        changed: (Int) -> Unit,
    ) {
        addCaption(title)
        val spinner = Spinner(this)
        spinner.contentDescription = title
        spinner.minimumHeight = toPixels(56)
        spinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        spinner.setSelection(selected.coerceAtLeast(0))
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) = changed(position)
            }
        contentLayout.addView(spinner)
    }

    private fun addBarcodeSettings(config: Alarm) {
        val codes = AlarmStore(this).getBarcodes().sortedBy { it.name }
        dropdown(
            "First barcode",
            listOf("None — normal timer") + codes.map { it.name },
            codes.indexOfFirst { it.id == config.barcodeId } + 1,
        ) { position ->
            val id = if (position == 0) 0 else codes[position - 1].id
            if (draft?.configuration?.barcodeId != id) {
                captureInputs()
                var updated = BarcodeLibrary.select(checkNotNull(draft).configuration, id, codes)
                if (updated.secondBarcodeId == id)
                    updated = BarcodeLibrary.selectSecond(updated, 0, codes)
                draft = draft?.copy(configuration = updated)
                showEditor()
            }
        }
        if (config.barcodeId == 0) return
        val seconds = codes.filter { it.id != config.barcodeId }
        dropdown(
            "Second barcode (optional)",
            listOf("None — one barcode only") + seconds.map { it.name },
            seconds.indexOfFirst { it.id == config.secondBarcodeId } + 1,
        ) { position ->
            val id = if (position == 0) 0 else seconds[position - 1].id
            if (draft?.configuration?.secondBarcodeId != id) {
                captureInputs()
                draft =
                    draft?.let {
                        it.copy(
                            configuration =
                                BarcodeLibrary.selectSecond(it.configuration, id, codes)
                                    .copy(scanIntervalMinutes = 1)
                        )
                    }
                showEditor()
            }
        }
        if (config.secondBarcodeId != 0)
            dropdown(
                "Minimum time between barcode scans",
                (0..30).map { "$it minutes" },
                config.scanIntervalMinutes,
            ) { value ->
                draft =
                    draft?.let {
                        it.copy(configuration = it.configuration.copy(scanIntervalMinutes = value))
                    }
            }
    }

    private fun savePreset() {
        captureInputs()
        val h = hours.toIntOrNull() ?: error("Enter hours")
        val m = minutes.toIntOrNull() ?: error("Enter minutes")
        val s = seconds.toIntOrNull() ?: error("Enter seconds")
        require(h in 0..168 && m in 0..59 && s in 0..59) {
            "Use hours 0–168 and minutes/seconds 0–59"
        }
        val preset = checkNotNull(draft)
        val interval =
            if (preset.reminderMinutes > 0)
                reminder.toIntOrNull()?.also {
                    require(it in 1..10080) { "Reminder interval must be 1–10080 minutes" }
                } ?: error("Enter reminder minutes")
            else 0
        val codes = AlarmStore(this).getBarcodes()
        val first =
            BarcodeLibrary.select(preset.configuration, preset.configuration.barcodeId, codes)
        val config = BarcodeLibrary.selectSecond(first, first.secondBarcodeId, codes)
        presets.save(
            preset.copy(
                durationMillis = (h * 3600L + m * 60 + s) * 1000,
                configuration = config,
                reminderMinutes = interval,
            )
        )
        draft = null
        showTimers()
    }

    private fun leaveEditor() {
        captureInputs()
        val current = draft ?: return
        val original =
            presets.all().find { it.id == current.id } ?: TimerPreset(current.id, 15 * 60_000L)
        val h = hours.toLongOrNull()
        val m = minutes.toLongOrNull()
        val s = seconds.toLongOrNull()
        val interval = if (current.reminderMinutes > 0) reminder.toIntOrNull() else 0
        if (
            h != null &&
                m != null &&
                s != null &&
                interval != null &&
                current.copy(
                    durationMillis = (h * 3600 + m * 60 + s) * 1000,
                    reminderMinutes = interval,
                ) == original
        ) {
            draft = null
            showTimers()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Discard timer changes?")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard") { _, _ ->
                draft = null
                showTimers()
            }
            .show()
    }

    @Deprecated("Use the existing native ringtone picker integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            draft =
                draft?.let {
                    it.copy(
                        configuration = it.configuration.copy(soundUri = uri?.toString().orEmpty())
                    )
                }
            showEditor()
        }
    }
}
