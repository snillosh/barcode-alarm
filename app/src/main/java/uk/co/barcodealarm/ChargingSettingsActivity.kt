package uk.co.barcodealarm

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Switch

class ChargingSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        try {
            ChargingTracking.maintain(this)
        } catch (_: Exception) {}
        render()
    }

    private fun render() {
        showPage(
            "Bedtime charging",
            "Optional charging observations between 20:00 and 03:00, in the phone’s local time.",
        )
        setBackAction { finish() }
        val toggle =
            Switch(this).apply {
                text = "Track bedtime charging"
                setTextColor(textColor)
                isChecked = ChargingTracking.enabled(this@ChargingSettingsActivity)
            }
        contentLayout.addView(toggle)
        addCaption(
            "While enabled, a silent notification appears during the window. The app screen can stay closed. Saved observations upload when Wellness Sync is enabled."
        )
        addCaption(
            "Estimated bedtime uses the last new connection still connected at 03:00. Already charging at 20:00, unplugged at 03:00, or interrupted tracking produces no estimate. This is not confirmed sleep onset."
        )
        addButton("Save tracking", primary = true) {
            try {
                ChargingTracking.setEnabled(this, toggle.isChecked)
                if (
                    toggle.isChecked &&
                        Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                )
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 430004)
                render()
            } catch (_: Exception) {
                showMessage("Could not schedule tracking. Check Alarms & reminders permission.")
            }
        }
        if (
            Build.VERSION.SDK_INT >= 31 &&
                !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            addButton("Allow Alarms & reminders") {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }
        addText(ChargingTracking.status(this), 14f)
        val counts =
            try {
                ChargingStore(this).use {
                    it.counts(WellnessSettings(this).configuration().serverUrl)
                }
            } catch (_: Exception) {
                null
            }
        counts?.let {
            addText("Charging / bedtime uploads pending: ${it.first} · Rejected: ${it.second}", 14f)
        }
        addButton("Wellness Sync") { startActivity(Intent(this, WellnessSyncActivity::class.java)) }
        addCaption(
            "Open the app once after installing. Force-stop, reboot, time-zone changes and tracking gaps may leave a night empty. No charging history is invented or backfilled."
        )
        addButton("Back") { finish() }
    }
}
