package uk.co.barcodealarm

import android.content.Context
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence, comparable to a small repository in a .NET application. SharedPreferences is
 * Android's private key/value store; the two lists are JSON strings. Keep the on-disk keys
 * unchanged when renaming Kotlin properties.
 */
class AlarmStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun getAlarms(): List<Alarm> {
        migrateBarcodeLibraryIfNeeded()
        return readAlarms()
    }

    /** Reserve IDs durably, including unsaved drafts; deleted IDs must not be reused. */
    fun nextAlarmId(): Int {
        val largestSavedId = getAlarms().maxOfOrNull { it.id } ?: 0
        val lastId = maxOf(preferences.getInt("last_alarm_id", 0), largestSavedId)
        check(lastId < Int.MAX_VALUE) { "No alarm IDs available" }
        val id = lastId + 1
        check(preferences.edit().putInt("last_alarm_id", id).commit()) {
            "Could not reserve alarm ID"
        }
        return id
    }

    fun getAlarm(id: Int): Alarm? {
        return getAlarms().find { alarm -> alarm.id == id }
    }

    fun saveAlarm(alarm: Alarm) {
        // Replace by ID, preserving the previous ordering behaviour (updated alarms go last).
        val otherAlarms = getAlarms().filterNot { existing -> existing.id == alarm.id }
        writeAlarms(otherAlarms + alarm)
    }

    fun recordPlaybackStart(alarmId: Int, startedAtElapsedMillis: Long) {
        // Keep the ringing queue's order: initialising playback must not move this alarm
        // behind another simultaneously ringing alarm, unlike ordinary editor saves.
        val alarms =
            getAlarms().map { alarm ->
                if (alarm.id == alarmId)
                    alarm.copy(playbackStartedAtElapsedMillis = startedAtElapsedMillis)
                else alarm
            }
        writeAlarms(alarms)
    }

    fun deleteAlarm(id: Int) {
        writeAlarms(getAlarms().filterNot { alarm -> alarm.id == id })
    }

    fun getBarcodes(): List<SavedBarcode> {
        migrateBarcodeLibraryIfNeeded()
        return readBarcodes()
    }

    fun addBarcode(name: String, value: String, format: String): SavedBarcode {
        val updatedLibrary = BarcodeLibrary.add(getBarcodes(), name, value, format)
        writeBarcodes(updatedLibrary)
        return updatedLibrary.last()
    }

    fun renameBarcode(id: Int, name: String) {
        writeBarcodes(BarcodeLibrary.rename(getBarcodes(), id, name))
    }

    fun deleteBarcode(id: Int) {
        val remainingBarcodes =
            BarcodeLibrary.delete(
                getBarcodes(),
                id,
                getAlarms() + OccurrenceStore(context).all().map { it.snapshot },
            )
        writeBarcodes(remainingBarcodes)
    }

    private fun readAlarms(): List<Alarm> {
        return AlarmJsonCodec.decode(preferences.getString("data", "[]").orEmpty())
    }

    private fun writeAlarms(alarms: List<Alarm>) {
        // commit() is deliberately synchronous: persist an occurrence before scheduling it.
        val saved = preferences.edit().putString("data", AlarmJsonCodec.encode(alarms)).commit()
        check(saved) { "Could not persist alarms" }
    }

    private fun readBarcodes(): List<SavedBarcode> {
        val jsonArray = JSONArray(preferences.getString("barcodes", "[]"))
        val barcodes = mutableListOf<SavedBarcode>()
        for (index in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(index)
            barcodes.add(
                SavedBarcode(
                    id = json.getInt("id"),
                    name = json.getString("name"),
                    barcodeValue = json.getString("code"),
                    barcodeFormat = json.getString("format"),
                )
            )
        }
        return barcodes
    }

    private fun encodeBarcodes(barcodes: List<SavedBarcode>): String {
        val jsonArray = JSONArray()
        for (barcode in barcodes) {
            val json = JSONObject()
            json.put("id", barcode.id)
            json.put("name", barcode.name)
            json.put("code", barcode.barcodeValue)
            json.put("format", barcode.barcodeFormat)
            jsonArray.put(json)
        }
        return jsonArray.toString()
    }

    private fun writeBarcodes(barcodes: List<SavedBarcode>) {
        val saved = preferences.edit().putString("barcodes", encodeBarcodes(barcodes)).commit()
        check(saved) { "Could not save barcode library" }
    }

    private fun migrateBarcodeLibraryIfNeeded() {
        if (preferences.getBoolean("barcode_library_migrated", false)) {
            return
        }
        val migration = BarcodeLibrary.migrate(readBarcodes(), readAlarms())

        // One transaction prevents a partially migrated library/alarm relationship.
        val saved =
            preferences
                .edit()
                .putString("data", AlarmJsonCodec.encode(migration.alarms))
                .putString("barcodes", encodeBarcodes(migration.barcodes))
                .putBoolean("barcode_library_migrated", true)
                .commit()
        check(saved) { "Could not migrate barcode library" }
    }

    fun getLastRestoredBootCount(): Int {
        return preferences.getInt("last_restored_boot_count", -1)
    }

    fun recordRestoredBoot(bootCount: Int) {
        check(preferences.edit().putInt("last_restored_boot_count", bootCount).commit()) {
            "Could not save restored boot identity"
        }
    }

    fun log(message: String) {
        val previousLog = preferences.getString("log", "").orEmpty()
        val updatedLog = (previousLog + "\n${Instant.now()} $message").takeLast(16_000)
        preferences.edit().putString("log", updatedLog).commit()
    }

    fun getDiagnosticLog(): String {
        return preferences.getString("log", "No events").orEmpty()
    }
}
