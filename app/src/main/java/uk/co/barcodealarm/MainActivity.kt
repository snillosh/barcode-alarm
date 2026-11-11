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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import com.google.zxing.integration.android.IntentIntegrator
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
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (store.getAlarms().any { alarm -> alarm.isRinging }) {
            startForegroundService(Intent(this, RingService::class.java))
            startActivity(Intent(this, RingActivity::class.java))
            return
        }
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
                store.saveAlarm(alarm.copy(isEnabled = false, scheduledAtMillis = 0))
            }
            addText("Exact alarm access is missing. Allow it in Settings, then enable your alarms.")
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            addText("Warning: alarm notifications are blocked. Open Settings.")
        }
    }

    private fun addAlarmCard(alarm: Alarm) {
        addCard {
            addCaption(if (alarm.isEnabled) "SCHEDULED • ONE TIME" else "READY WHEN YOU ARE")
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
            addCaption(
                if (alarm.isEnabled) "Enabled · scan to dismiss"
                else "Disabled · enable for the next occurrence"
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
        showPage("Set your alarm", "One time. Your chosen place to start.")
        setBackAction { navigateBack() }
        addCaption("WAKE-UP TIME")
        val picker = TimePicker(this)
        picker.setIs24HourView(true)
        picker.hour = draft.hour
        picker.minute = draft.minute
        timePicker = picker
        contentLayout.addView(picker)
        addSpace(12)
        addCaption("DISMISSAL BARCODE")
        addBarcodeDropdown(draft)
        addButton("Manage barcodes") {
            captureEditorTime()
            showBarcodeLibrary()
        }
        addButton("Choose alarm sound") { openSoundPicker(draft) }
        addSpace(12)
        addButton("Save alarm", primary = true) { runUiAction { saveAlarmDraft() } }
        addButton("Cancel") { navigateBack() }
    }

    private fun addBarcodeDropdown(draft: Alarm) {
        val library =
            store.getBarcodes().sortedBy { barcode -> barcode.name.lowercase(Locale.ROOT) }
        if (library.isEmpty()) {
            addCaption("Your library is empty. Add a barcode below before saving this alarm.")
        }
        // Index zero is a prompt, not a valid barcode selection.
        val names = listOf("Select a barcode…") + library.map { barcode -> barcode.name }
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
                    alarmDraft = alarmDraft?.copy(barcodeId = selectedId)
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
        val updatedAlarm = BarcodeLibrary.select(draft, draft.barcodeId, store.getBarcodes())
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
            val alarmCount = store.getAlarms().count { alarm -> alarm.barcodeId == barcode.id }
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
