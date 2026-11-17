package uk.co.barcodealarm

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native settings only. WorkManager owns all network requests, including connection tests. */
class WellnessSyncActivity : BaseActivity() {
    private val settings by lazy { WellnessSettings(this) }
    private lateinit var server: EditText
    private lateinit var credential: EditText
    private lateinit var enabled: Switch
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh =
        object : Runnable {
            override fun run() {
                refreshStatus()
                handler.postDelayed(this, 2000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showPage("Wellness Sync", "Optional background uploads. Your alarms always work offline.")
        setBackAction { finish() }
        val config = settings.configuration()
        addCaption("SERVER URL")
        server =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setText(config.serverUrl)
                setTextColor(textColor)
            }
        contentLayout.addView(server)
        addCaption("API KEY")
        credential =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                isSaveEnabled = false
                setSingleLine(true)
                hint = "Leave blank to keep the saved key"
                setTextColor(textColor)
            }
        contentLayout.addView(credential)
        enabled =
            Switch(this).apply {
                text = "Enable background synchronisation"
                setTextColor(textColor)
                isChecked = config.enabled
            }
        contentLayout.addView(enabled)
        addCaption("Changes take effect when you save. Use HTTPS with a trusted certificate.")
        addButton("Save connection", primary = true) {
            try {
                settings.save(
                    server.text.toString(),
                    credential.text.toString().trim().takeIf { it.isNotEmpty() },
                    enabled.isChecked,
                )
                credential.text.clear()
                server.setText(settings.configuration().serverUrl)
                WellnessSync.maintain(this)
                showMessage("Connection settings saved")
            } catch (_: Exception) {
                showMessage(
                    "Could not save. Check the HTTPS URL and API key, or replace an unreadable key."
                )
            }
            refreshStatus()
        }
        addButton("Test saved connection") {
            try {
                settings.manualRetry()
                settings.status(
                    settings.configuration(),
                    "Connection test queued; waiting for network",
                )
                WellnessSync.enqueue(this, true)
            } catch (_: Exception) {
                showMessage("Could not queue connection test")
            }
            refreshStatus()
        }
        addButton("Sync now / retry pending") {
            try {
                val saved = settings.configuration()
                if (!saved.enabled) showMessage("Enable sync and save first")
                else {
                    settings.manualRetry()
                    OccurrenceStore(this).retryRejected(saved.serverUrl)
                    ChargingStore(this).use { it.retry(saved.serverUrl) }
                    settings.status(saved, "Sync queued; waiting for network")
                    WellnessSync.maintain(this)
                }
            } catch (_: Exception) {
                showMessage("Could not queue sync; records remain on this device")
            }
            refreshStatus()
        }
        addButton("Clear saved API key", danger = true) {
            AlertDialog.Builder(this)
                .setMessage("Clear the saved API key? Local alarm history will be retained.")
                .setPositiveButton("Clear") { _, _ ->
                    try {
                        settings.clearCredential()
                        credential.text.clear()
                        WellnessSync.maintain(this)
                    } catch (_: Exception) {
                        showMessage("Could not clear credential")
                    }
                    refreshStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        status = addText("", 14f)
        addCaption(
            "Local history starts with this version. Older completed alarms were not retained by the app."
        )
        addCaption(
            "The latest 1,000 uploaded records are retained locally; pending and rejected records are retained up to the storage limit."
        )
        addButton("Back to settings") { finish() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        try {
            val config = settings.configuration()
            val store = OccurrenceStore(this)
            val history = store.history()
            val charging = ChargingStore(this).use { it.counts(config.serverUrl) }
            val pending = history.count { !it.acknowledgedBy.containsKey(config.serverUrl) } + charging.first
            val rejected = history.count { it.rejectedBy.containsKey(config.serverUrl) } + charging.second
            val success = settings.lastSuccess()
            val time =
                if (success == 0L) "Never"
                else
                    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(success))
            status.text =
                "${settings.status()}\nKey: ${if (settings.hasCredential()) "Saved securely" else "Not configured"}" +
                    "\nPending: $pending · Rejected: $rejected\nLast successful sync: $time" +
                    if (store.hasCaptureWarning())
                        "\nLocal history capture/recovery needs attention; some completions may be missing."
                    else ""
        } catch (_: Exception) {
            status.text = "Local sync history needs attention. Alarm operation is independent."
        }
    }
}
