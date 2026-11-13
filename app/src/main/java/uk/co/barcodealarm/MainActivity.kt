package uk.co.barcodealarm

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import com.google.zxing.integration.android.IntentIntegrator
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Coordinates the ordinary app pages. Each show... method builds one native view tree. Rendering
 * methods add controls; action methods validate, save, or navigate. The alarm service runs
 * independently of this Activity (Android's screen/window controller).
 */
class MainActivity : BaseActivity() {
    companion object {
        private const val SOUND_PICKER_REQUEST = 42
        private const val NOTIFICATION_PERMISSION_REQUEST = 4
        private const val CAMERA_PERMISSION_REQUEST = 5
    }

    // `by lazy` creates this repository on first use, after Android attaches the Activity context.
    private val store by lazy { AlarmStore(this) }
    private var currentPage = Page.HOME
    private var alarmDraft: Alarm? = null
    private var timePicker: TimePicker? = null
    private var discardDialog: AlertDialog? = null

    // A scan is not saved until the user supplies a valid library name.
    private var scannedBarcodeValue: String? = null
    private var scannedBarcodeFormat: String? = null
    private var scannedBarcodeName = ""
    private var barcodeNameInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runUiAction {
            OccurrenceManager(this).migrateLegacy()
            OccurrenceManager(this).restore(false)
        }
        restorePageState(savedInstanceState)
        when (currentPage) {
            Page.HOME -> showHomePage()
            Page.SETTINGS -> showSettingsPage()
            Page.EDITOR -> showAlarmEditor()
            Page.BARCODES -> showBarcodeLibrary()
        }
        if (scannedBarcodeValue != null) {
            showScannedBarcodeNameDialog()
        }
    }

    private fun restorePageState(state: Bundle?) {
        // Bundle is Android's temporary recreation state, not the permanent alarm repository.
        val editingAlarmId = state?.getInt("editing", -1) ?: -1
        if (state != null && editingAlarmId >= 0) {
            val savedAlarm = store.getAlarm(editingAlarmId) ?: Alarm(editingAlarmId, 7, 0, "", "")
            alarmDraft =
                savedAlarm.copy(
                    hour = state.getInt("hour"),
                    minute = state.getInt("minute"),
                    barcodeValue = state.getString("code", ""),
                    barcodeFormat = state.getString("format", ""),
                    soundUri = state.getString("sound", ""),
                    barcodeId = state.getInt("barcodeId"),
                    secondBarcodeId = state.getInt("secondId"),
                    secondBarcodeValue = state.getString("secondValue", ""),
                    secondBarcodeFormat = state.getString("secondFormat", ""),
                    scanIntervalMinutes = state.getInt("scanInterval", 1),
                    verificationEnabled = state.getBoolean("verify"),
                    verificationDelayMinutes = state.getInt("verifyDelay", 5),
                    verificationResponseMinutes = state.getInt("verifyResponse", 2),
                    repeatDays =
                        state
                            .getIntArray("repeatDays")
                            ?.filter { value -> value in 1..7 }
                            ?.map(DayOfWeek::of)
                            ?.toSet() ?: savedAlarm.repeatDays,
                )
        }
        currentPage = Navigation.restorePage(state?.getString("page"), alarmDraft != null)
        scannedBarcodeValue = state?.getString("pendingCode")
        scannedBarcodeFormat = state?.getString("pendingFormat")
        scannedBarcodeName = state?.getString("pendingName").orEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", currentPage.name)
        outState.putString("pendingCode", scannedBarcodeValue)
        outState.putString("pendingFormat", scannedBarcodeFormat)
        outState.putString("pendingName", barcodeNameInput?.text?.toString() ?: scannedBarcodeName)
        val draft = alarmDraft
        if (draft != null) {
            outState.putInt("editing", draft.id)
            outState.putInt("hour", timePicker?.hour ?: draft.hour)
            outState.putInt("minute", timePicker?.minute ?: draft.minute)
            outState.putString("code", draft.barcodeValue)
            outState.putString("format", draft.barcodeFormat)
            outState.putString("sound", draft.soundUri)
            outState.putInt("barcodeId", draft.barcodeId)
            outState.putInt("secondId", draft.secondBarcodeId)
            outState.putString("secondValue", draft.secondBarcodeValue)
            outState.putString("secondFormat", draft.secondBarcodeFormat)
            outState.putInt("scanInterval", draft.scanIntervalMinutes)
            outState.putBoolean("verify", draft.verificationEnabled)
            outState.putInt("verifyDelay", draft.verificationDelayMinutes)
            outState.putInt("verifyResponse", draft.verificationResponseMinutes)
            outState.putIntArray(
                "repeatDays",
                draft.repeatDays.map { day -> day.value }.toIntArray(),
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (OccurrenceStore(this).all().any { it.audible })
            startForegroundService(Intent(this, RingService::class.java))
        // Refresh permission status after returning from Android Settings, without replacing a
        // draft.
        when (currentPage) {
            Page.SETTINGS -> showSettingsPage()
            Page.HOME -> showHomePage()
            else -> Unit
        }
    }

    override fun onDestroy() {
        discardDialog?.dismiss()
        super.onDestroy()
    }

    private fun showHomePage() {
        currentPage = Page.HOME
        showPage("Your alarms", "A deliberate start to your day.")
        timePicker = null
        addSchedulingWarnings()
        for (occurrence in OccurrenceStore(this).all()) {
            if (occurrence.active || occurrence.stage == OccurrenceStage.VERIFY_RESPONSE) {
                addButton(if (occurrence.active) "Open active alarm" else "Are you still awake?") {
                    startActivity(
                        Intent(this, RingActivity::class.java).putExtra("occurrence", occurrence.id)
                    )
                }
            }
        }

        val alarms = store.getAlarms().sortedBy { alarm -> alarm.hour * 60 + alarm.minute }
        if (alarms.isEmpty()) {
            addCard {
                addText("Make mornings intentional", 22f)
                addCaption("Set a time. Register a barcode somewhere worth getting up for.")
            }
        }
        for (alarm in alarms) {
            addAlarmCard(alarm)
        }
        addButton("＋  Add alarm", primary = true) { createAlarmDraft() }
        addButton("Barcodes") { showBarcodeLibrary() }
        addButton("Test alarm") { showTestAlarmConfirmation() }
        addButton("Settings & diagnostics") { showSettingsPage() }
    }

    private fun addSchedulingWarnings() {
        if (
            Build.VERSION.SDK_INT >= 31 &&
                !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            for (alarm in store.getAlarms().filter { savedAlarm -> savedAlarm.isEnabled }) {
                if (!alarm.isRinging) AlarmScheduler(this).cancel(alarm)
                store.saveAlarm(
                    alarm.copy(isEnabled = false, scheduledAtMillis = 0, isSnoozed = false)
                )
            }
            addText("Exact alarm access is missing. Allow it in Settings, then enable your alarms.")
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            addText("Warning: alarm notifications are blocked. Open Settings.")
        }
    }

    private fun addAlarmCard(alarm: Alarm) {
        addCard {
            val status =
                when {
                    alarm.isSnoozed -> "SNOOZED"
                    !alarm.isEnabled -> "READY WHEN YOU ARE"
                    alarm.repeatDays.isNotEmpty() -> "REPEATING"
                    else -> "SCHEDULED • ONE TIME"
                }
            addCaption(status)
            val timeRow = LinearLayout(this)
            timeRow.gravity = Gravity.CENTER_VERTICAL
            val clock = TextView(this)
            clock.text = "%02d:%02d".format(alarm.hour, alarm.minute)
            clock.textSize = 52f
            clock.setTextColor(textColor)
            clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            timeRow.addView(clock, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            timeRow.addView(createAlarmEnabledSwitch(alarm))
            contentLayout.addView(timeRow)

            val selectedBarcode =
                store.getBarcodes().find { barcode -> barcode.id == alarm.barcodeId }
            addCaption(selectedBarcode?.name ?: "No barcode selected")
            addCaption(RepeatDays.describe(alarm.repeatDays))
            if (alarm.isEnabled && alarm.scheduledAtMillis > 0) {
                val nextTime =
                    Instant.ofEpochMilli(alarm.scheduledAtMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))
                addCaption(if (alarm.isSnoozed) "Snoozed until $nextTime" else "Next: $nextTime")
            }
            addCaption(
                if (alarm.isEnabled) {
                    if (alarm.barcodeValue.isEmpty()) "Enabled · tap to dismiss"
                    else "Enabled · scan to dismiss"
                } else "Disabled · enable for the next occurrence"
            )
            addButtonRow {
                addButton("Edit alarm") {
                    alarmDraft = alarm
                    showAlarmEditor()
                }
                addButton("Delete", danger = true) { runUiAction { deleteAlarm(alarm) } }
            }
        }
    }

    private fun createAlarmEnabledSwitch(alarm: Alarm): Switch {
        val toggle = Switch(this)
        toggle.contentDescription = "Enable alarm at %02d:%02d".format(alarm.hour, alarm.minute)
        toggle.isChecked = alarm.isEnabled
        toggle.minimumWidth = toPixels(64)
        toggle.minimumHeight = toPixels(56)
        toggle.thumbTintList =
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, secondaryTextColor),
            )
        // Set the initial state BEFORE attaching the listener; rendering must not schedule an
        // alarm.
        toggle.setOnCheckedChangeListener { _, isChecked ->
            runUiAction {
                val scheduler = AlarmScheduler(this)
                if (isChecked) scheduler.enable(alarm) else scheduler.disable(alarm)
            }
            showHomePage()
        }
        return toggle
    }

    private fun deleteAlarm(alarm: Alarm) {
        check(!alarm.isRinging)
        AlarmScheduler(this).cancel(alarm)
        OccurrenceManager(this).cancelDefinition(alarm.id)
        store.deleteAlarm(alarm.id)
        showHomePage()
    }

    private fun createAlarmDraft() {
        val nextId = (store.getAlarms().maxOfOrNull { alarm -> alarm.id } ?: 0) + 1
        alarmDraft = Alarm(id = nextId, hour = 7, minute = 0, barcodeValue = "", barcodeFormat = "")
        showAlarmEditor()
    }

    private fun captureEditorTime() {
        val picker = timePicker ?: return
        picker.clearFocus()
        alarmDraft = alarmDraft?.copy(hour = picker.hour, minute = picker.minute)
    }

    private fun navigateBack() {
        if (currentPage == Page.EDITOR) captureEditorTime()
        val draft = alarmDraft
        val hasChanges =
            draft != null && Navigation.hasUnsavedChanges(draft, store.getAlarm(draft.id))
        when (Navigation.back(currentPage, draft != null, hasChanges)) {
            BackDestination.HOME -> {
                alarmDraft = null
                showHomePage()
            }
            BackDestination.EDITOR -> showAlarmEditor()
            BackDestination.CONFIRM_DISCARD -> showDiscardConfirmation()
            else -> Unit
        }
    }

    private fun showDiscardConfirmation() {
        if (discardDialog?.isShowing == true) return
        discardDialog =
            AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("Your unsaved alarm changes will be lost.")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard") { _, _ ->
                    alarmDraft = null
                    showHomePage()
                }
                .show()
    }

    private fun showAlarmEditor() {
        val draft = alarmDraft ?: return
        currentPage = Page.EDITOR
        showPage("Set your alarm", "Your chosen time. Your chosen days.")
        setBackAction { navigateBack() }
        addCaption("WAKE-UP TIME")
        val picker = TimePicker(this)
        picker.setIs24HourView(true)
        picker.hour = draft.hour
        picker.minute = draft.minute
        timePicker = picker
        contentLayout.addView(picker)
        addSpace(12)
        addRepeatDaySelector(draft)
        addCaption("First barcode")
        addBarcodeDropdown(draft)
        addSecondStageSettings(draft)
        addVerificationSettings(draft)
        addButton("Manage barcodes") {
            captureEditorTime()
            showBarcodeLibrary()
        }
        addButton("Choose alarm sound") { openSoundPicker(draft) }
        addSpace(12)
        addButton("Save alarm", primary = true) { runUiAction { saveAlarmDraft() } }
        addButton("Cancel") { navigateBack() }
    }

    private fun addRepeatDaySelector(draft: Alarm) {
        addCaption("REPEAT")
        val summary = addCaption(RepeatDays.describe(draft.repeatDays))
        for (daysInRow in DayOfWeek.values().toList().chunked(4)) {
            addButtonRow {
                for (day in daysInRow) {
                    val checkbox = CheckBox(this)
                    checkbox.text = RepeatDays.shortName(day)
                    checkbox.contentDescription =
                        "Repeat on ${day.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}"
                    checkbox.setTextColor(textColor)
                    checkbox.minimumHeight = toPixels(56)
                    checkbox.isChecked = day in draft.repeatDays
                    checkbox.setOnCheckedChangeListener { _, isChecked ->
                        val current = alarmDraft ?: return@setOnCheckedChangeListener
                        val updatedDays =
                            if (isChecked) current.repeatDays + day else current.repeatDays - day
                        alarmDraft = current.copy(repeatDays = updatedDays)
                        summary.text = RepeatDays.describe(updatedDays)
                    }
                    contentLayout.addView(checkbox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
            }
        }
        addCaption("Leave all days off for a one-time alarm.")
        addSpace(12)
    }

    private fun minuteSelector(
        title: String,
        current: Int,
        values: List<Int>,
        changed: (Int) -> Unit,
    ) {
        addCaption(title)
        val spinner = Spinner(this)
        val adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, values.map { "$it minutes" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
        spinner.minimumHeight = toPixels(56)
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    changed(values[position])
                }
            }
        contentLayout.addView(spinner)
    }

    private fun addSecondStageSettings(draft: Alarm) {
        if (draft.barcodeId == 0) return
        addCaption("Second barcode (optional)")
        val codes = store.getBarcodes().filter { it.id != draft.barcodeId }.sortedBy { it.name }
        val spinner = Spinner(this)
        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf("None — one barcode only") + codes.map { it.name },
            )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(codes.indexOfFirst { it.id == draft.secondBarcodeId } + 1)
        spinner.minimumHeight = toPixels(56)
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = if (position == 0) null else codes[position - 1]
                    if (alarmDraft?.secondBarcodeId != (selected?.id ?: 0)) {
                        captureEditorTime()
                        alarmDraft =
                            alarmDraft?.copy(
                                secondBarcodeId = selected?.id ?: 0,
                                secondBarcodeValue = selected?.barcodeValue.orEmpty(),
                                secondBarcodeFormat = selected?.barcodeFormat.orEmpty(),
                                scanIntervalMinutes = 1,
                            )
                        showAlarmEditor()
                    }
                }
            }
        contentLayout.addView(spinner)
        if (draft.secondBarcodeId != 0)
            minuteSelector(
                "Minimum time between barcode scans",
                draft.scanIntervalMinutes,
                (0..30).toList(),
            ) {
                alarmDraft = alarmDraft?.copy(scanIntervalMinutes = it)
            }
    }

    private fun addVerificationSettings(draft: Alarm) {
        val checkbox = CheckBox(this)
        checkbox.text = "Enable wake-up verification"
        checkbox.setTextColor(textColor)
        checkbox.minimumHeight = toPixels(56)
        checkbox.isChecked = draft.verificationEnabled
        checkbox.setOnCheckedChangeListener { _, checked ->
            captureEditorTime()
            alarmDraft = alarmDraft?.copy(verificationEnabled = checked)
            showAlarmEditor()
        }
        contentLayout.addView(checkbox)
        if (draft.verificationEnabled) {
            minuteSelector(
                "Verify after dismissal (minutes)",
                draft.verificationDelayMinutes,
                (1..60).toList(),
            ) {
                alarmDraft = alarmDraft?.copy(verificationDelayMinutes = it)
            }
            minuteSelector(
                "Time allowed to respond (minutes)",
                draft.verificationResponseMinutes,
                (1..30).toList(),
            ) {
                alarmDraft = alarmDraft?.copy(verificationResponseMinutes = it)
            }
        }
    }

    private fun addBarcodeDropdown(draft: Alarm) {
        val library =
            store.getBarcodes().sortedBy { barcode -> barcode.name.lowercase(Locale.ROOT) }
        if (library.isEmpty()) {
            addCaption("No saved barcodes. This alarm can use the normal Dismiss button.")
        }
        // Index zero selects a normal alarm with no scanning requirement.
        val names = listOf("None — normal alarm") + library.map { barcode -> barcode.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val dropdown = Spinner(this)
        dropdown.contentDescription = "Dismissal barcode"
        dropdown.minimumHeight = toPixels(56)
        dropdown.adapter = adapter
        val selectedIndex = library.indexOfFirst { barcode -> barcode.id == draft.barcodeId }
        dropdown.setSelection(if (selectedIndex < 0) 0 else selectedIndex + 1)
        dropdown.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selectedId = if (position == 0) 0 else library[position - 1].id
                    if (alarmDraft?.barcodeId != selectedId) {
                        captureEditorTime()
                        var updated =
                            BarcodeLibrary.select(checkNotNull(alarmDraft), selectedId, library)
                        if (updated.secondBarcodeId == selectedId) {
                            updated = BarcodeLibrary.selectSecond(updated, 0, library)
                        }
                        alarmDraft = updated
                        showAlarmEditor()
                    }
                }
            }
        contentLayout.addView(dropdown)
    }

    private fun openSoundPicker(draft: Alarm) {
        captureEditorTime()
        val existingSound = if (draft.soundUri.isNotEmpty()) Uri.parse(draft.soundUri) else null
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingSound)
        startActivityForResult(intent, SOUND_PICKER_REQUEST)
    }

    private fun saveAlarmDraft() {
        captureEditorTime()
        val draft = checkNotNull(alarmDraft)
        require(draft.scanIntervalMinutes in 0..30) { "Scan interval must be 0–30 minutes" }
        require(draft.verificationDelayMinutes in 1..60) {
            "Verification delay must be 1–60 minutes"
        }
        require(draft.verificationResponseMinutes in 1..30) {
            "Response allowance must be 1–30 minutes"
        }
        var updatedAlarm = BarcodeLibrary.select(draft, draft.barcodeId, store.getBarcodes())
        updatedAlarm =
            BarcodeLibrary.selectSecond(
                updatedAlarm,
                updatedAlarm.secondBarcodeId,
                store.getBarcodes(),
            )
        check(store.getAlarm(updatedAlarm.id)?.isRinging != true) { "Cannot edit a ringing alarm" }
        if (updatedAlarm.isEnabled) {
            AlarmScheduler(this).enable(updatedAlarm)
        } else {
            store.saveAlarm(updatedAlarm)
        }
        alarmDraft = null
        showHomePage()
    }

    @Deprecated("Android result API retained for the existing native Activity/scanner integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            if (scanResult.contents != null) {
                scannedBarcodeValue = scanResult.contents
                scannedBarcodeFormat = scanResult.formatName
                scannedBarcodeName = ""
                showBarcodeLibrary()
                showScannedBarcodeNameDialog()
            } else {
                showBarcodeLibrary()
            }
            return
        }
        if (requestCode == SOUND_PICKER_REQUEST && resultCode == RESULT_OK) {
            val selectedUri =
                data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            alarmDraft = alarmDraft?.copy(soundUri = selectedUri?.toString().orEmpty())
            showAlarmEditor()
        }
    }

    private fun showBarcodeLibrary() {
        currentPage = Page.BARCODES
        timePicker = null
        showPage("Barcodes", "Familiar names. One less thing to think about.")
        setBackAction { navigateBack() }
        val library =
            store.getBarcodes().sortedBy { barcode -> barcode.name.lowercase(Locale.ROOT) }
        if (library.isEmpty()) {
            addCard {
                addText("Your barcode library", 22f)
                addCaption(
                    "Scan an everyday object or QR code and give it a name, such as Home bathroom shampoo."
                )
            }
        }
        for (barcode in library) addBarcodeCard(barcode)
        addButton("Scan new barcode", primary = true) { runUiAction { openBarcodeScanner() } }
        addButton(if (alarmDraft != null) "Back to alarm" else "Back to alarms") { navigateBack() }
    }

    private fun addBarcodeCard(barcode: SavedBarcode) {
        addCard {
            addText(barcode.name, 22f)
            addCaption(barcode.barcodeFormat.replace('_', ' '))
            val alarmCount =
                store.getAlarms().count { alarm ->
                    alarm.barcodeId == barcode.id || alarm.secondBarcodeId == barcode.id
                }
            addCaption(if (alarmCount == 1) "Used by 1 alarm" else "Used by $alarmCount alarms")
            addButtonRow {
                addButton("Rename") { showRenameBarcodeDialog(barcode) }
                addButton("Delete", danger = true) { confirmBarcodeDeletion(barcode, alarmCount) }
            }
        }
    }

    private fun confirmBarcodeDeletion(barcode: SavedBarcode, alarmCount: Int) {
        if (alarmCount > 0) {
            showMessage(
                "This barcode is used by an alarm. Change that alarm's barcode or delete the alarm first."
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${barcode.name}?")
            .setMessage("Remove this barcode from your library?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                runUiAction {
                    store.deleteBarcode(barcode.id)
                    showBarcodeLibrary()
                }
            }
            .show()
    }

    private fun showRenameBarcodeDialog(barcode: SavedBarcode) {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(barcode.name)
        input.hint = "Barcode name"
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Rename barcode")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
        // Override Save after showing: invalid input must leave the dialog open for correction.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runUiAction {
                    store.renameBarcode(barcode.id, input.text.toString())
                    dialog.dismiss()
                    showBarcodeLibrary()
                }
            }
        }
        dialog.show()
    }

    private fun showScannedBarcodeNameDialog() {
        val value = scannedBarcodeValue ?: return
        val format = scannedBarcodeFormat ?: return
        val duplicate =
            store.getBarcodes().find { barcode ->
                AlarmRules.barcodesMatch(barcode.barcodeValue, barcode.barcodeFormat, value, format)
            }
        if (duplicate != null) {
            showMessage("Already saved as ${duplicate.name}")
            alarmDraft = alarmDraft?.copy(barcodeId = duplicate.id)
            scannedBarcodeValue = null
            scannedBarcodeFormat = null
            return
        }
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = "e.g. Home bathroom shampoo"
        input.setText(scannedBarcodeName)
        barcodeNameInput = input
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Name your barcode")
                .setView(input)
                .setNegativeButton("Cancel") { _, _ -> clearPendingBarcode() }
                .setPositiveButton("Save", null)
                .create()
        dialog.setOnCancelListener { clearPendingBarcode() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runUiAction {
                    val barcode = store.addBarcode(input.text.toString(), value, format)
                    alarmDraft = alarmDraft?.copy(barcodeId = barcode.id)
                    clearPendingBarcode()
                    dialog.dismiss()
                    showBarcodeLibrary()
                }
            }
        }
        dialog.show()
    }

    private fun clearPendingBarcode() {
        scannedBarcodeValue = null
        scannedBarcodeFormat = null
        scannedBarcodeName = ""
        barcodeNameInput = null
    }

    private fun showTestAlarmConfirmation() {
        val alarm = store.getAlarms().firstOrNull { savedAlarm -> !savedAlarm.isRinging }
        if (alarm == null) {
            showMessage("Save an alarm with a barcode first")
            return
        }
        val alarmTime = "${alarm.hour}:${alarm.minute.toString().padStart(2, '0')}"
        AlertDialog.Builder(this)
            .setMessage(
                "Schedule $alarmTime for 10 seconds from now? This replaces its scheduled occurrence and requires barcode or maths dismissal."
            )
            .setPositiveButton("Test") { _, _ ->
                runUiAction {
                    AlarmScheduler(this).enable(alarm, System.currentTimeMillis() + 10_000)
                    showHomePage()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsPage() {
        currentPage = Page.SETTINGS
        showPage("Settings", "A few essentials for dependable mornings.")
        setBackAction { navigateBack() }
        addAppearanceSettings()
        addPlaybackSettings()
        addCaption("PERMISSIONS & ACCESS")
        addPermissionSettings()
        addCaption("BATTERY & DEVICE")
        addCaption(
            "In app settings, open Battery and select Unrestricted. After reboot, unlock once. Missed alarms are disabled; review diagnostics."
        )
        addButton("Battery / app settings") { openAppSettings() }
        addButton("Test alarm") { showTestAlarmConfirmation() }
        addSpace(16)
        addCaption("LOCAL DIAGNOSTICS")
        addCard { addText(store.getDiagnosticLog(), 12f).typeface = Typeface.MONOSPACE }
        addButton("Back to alarms") { navigateBack() }
    }

    private fun addPlaybackSettings() {
        addCaption("ALARM PLAYBACK")
        addCard {
            val settingsStore = PlaybackSettingsStore(this)
            var settings = settingsStore.read()
            val toggle = Switch(this)
            toggle.text = "Gradual volume increase"
            toggle.textSize = 18f
            toggle.setTextColor(textColor)
            toggle.minimumHeight = toPixels(56)
            toggle.isChecked = settings.gradualVolumeEnabled
            contentLayout.addView(toggle)
            addCaption(
                "Start at 20% playback gain and gradually reach your normal alarm level. System alarm volume stays unchanged."
            )
            addCaption("Time to full volume")
            val durations = PlaybackSettings.SUPPORTED_DURATIONS_SECONDS
            val labels = listOf("30 seconds", "1 minute", "2 minutes", "5 minutes")
            val dropdown = Spinner(this)
            dropdown.contentDescription = "Gradual volume duration"
            dropdown.minimumHeight = toPixels(56)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            dropdown.adapter = adapter
            dropdown.setSelection(durations.indexOf(settings.rampDurationSeconds))
            dropdown.isEnabled = settings.gradualVolumeEnabled
            dropdown.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val duration = durations[position]
                        if (duration != settings.rampDurationSeconds)
                            runUiAction {
                                val updated = settings.copy(rampDurationSeconds = duration)
                                settingsStore.save(updated)
                                settings = updated
                            }
                        dropdown.setSelection(durations.indexOf(settings.rampDurationSeconds))
                    }
                }
            contentLayout.addView(dropdown)
            toggle.setOnCheckedChangeListener { _, isChecked ->
                runUiAction {
                    val updated = settings.copy(gradualVolumeEnabled = isChecked)
                    settingsStore.save(updated)
                    settings = updated
                }
                toggle.isChecked = settings.gradualVolumeEnabled
                dropdown.isEnabled = settings.gradualVolumeEnabled
            }
        }
    }

    private fun addAppearanceSettings() {
        addCaption("APPEARANCE")
        addCard {
            val toggle = Switch(this)
            toggle.text = "Dark mode"
            toggle.textSize = 18f
            toggle.setTextColor(textColor)
            toggle.minimumHeight = toPixels(56)
            toggle.isChecked = isDarkModeEnabled
            toggle.setOnCheckedChangeListener { _, isChecked ->
                runUiAction {
                    val saved =
                        appearancePreferences.edit().putBoolean("dark_mode", isChecked).commit()
                    check(saved) { "Could not save appearance" }
                    // Recreation applies the native theme; onSaveInstanceState preserves this page.
                    recreate()
                }
            }
            contentLayout.addView(toggle)
            addCaption("Use a darker palette across the app. Your choice is saved on this device.")
        }
    }

    private fun addPermissionSettings() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val exactAlarmsAllowed =
            Build.VERSION.SDK_INT < 31 ||
                getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val fullScreenAllowed =
            Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
        val cameraAllowed =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        addPermissionCard("Exact alarms", exactAlarmsAllowed, "Allow exact alarms") {
            requestExactAlarmAccess()
        }
        addPermissionCard(
            "Notifications",
            notificationManager.areNotificationsEnabled(),
            "Allow notifications",
        ) {
            requestNotificationAccess()
        }
        addPermissionCard("Lock-screen display", fullScreenAllowed, "Full-screen alarm settings") {
            requestFullScreenAccess()
        }
        addPermissionCard("Camera", cameraAllowed, "Allow camera") {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun addPermissionCard(
        title: String,
        allowed: Boolean,
        buttonText: String,
        onClick: () -> Unit,
    ) {
        addCard {
            addText("$title  ·  ${if (allowed) "Allowed" else "Needs access"}")
            addButton(buttonText, onClick = onClick)
        }
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= 31) {
            runUiAction {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        }
    }

    private fun requestFullScreenAccess() {
        if (Build.VERSION.SDK_INT >= 34) {
            runUiAction {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }
}
