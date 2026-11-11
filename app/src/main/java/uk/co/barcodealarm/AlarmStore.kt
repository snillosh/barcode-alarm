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
class AlarmStore(context: Context) {
    private val preferences = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun getAlarms(): List<Alarm> {
        migrateBarcodeLibraryIfNeeded()
        return readAlarms()
    }

    fun getAlarm(id: Int): Alarm? {
        return getAlarms().find { alarm -> alarm.id == id }
    }

    fun saveAlarm(alarm: Alarm) {
        // Replace by ID, preserving the previous ordering behaviour (updated alarms go last).
        val otherAlarms = getAlarms().filterNot { existing -> existing.id == alarm.id }
        writeAlarms(otherAlarms + alarm)
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
        val remainingBarcodes = BarcodeLibrary.delete(getBarcodes(), id, getAlarms())
        writeBarcodes(remainingBarcodes)
    }

    private fun readAlarms(): List<Alarm> {
        val jsonArray = JSONArray(preferences.getString("data", "[]"))
        val alarms = mutableListOf<Alarm>()
        for (index in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(index)
            alarms.add(
                Alarm(
                    id = json.getInt("id"),
                    hour = json.getInt("hour"),
                    minute = json.getInt("minute"),
                    barcodeValue = json.getString("code"),
                    barcodeFormat = json.getString("format"),
                    soundUri = json.optString("sound"),
                    isEnabled = json.optBoolean("enabled"),
                    scheduledAtMillis = json.optLong("due"),
                    isRinging = json.optBoolean("ringing"),
                    mathsStartedAtElapsedMillis = json.optLong("started", -1),
                    correctAnswerCount = json.optInt("correct"),
                    barcodeId = json.optInt("barcodeId"),
                )
            )
        }
        return alarms
    }

    private fun encodeAlarms(alarms: List<Alarm>): String {
        val jsonArray = JSONArray()
        for (alarm in alarms) {
            val json = JSONObject()
            json.put("id", alarm.id)
            json.put("hour", alarm.hour)
            json.put("minute", alarm.minute)
            json.put("code", alarm.barcodeValue)
            json.put("format", alarm.barcodeFormat)
            json.put("sound", alarm.soundUri)
            json.put("enabled", alarm.isEnabled)
            json.put("due", alarm.scheduledAtMillis)
            json.put("ringing", alarm.isRinging)
            json.put("started", alarm.mathsStartedAtElapsedMillis)
            json.put("correct", alarm.correctAnswerCount)
            json.put("barcodeId", alarm.barcodeId)
            jsonArray.put(json)
        }
        return jsonArray.toString()
    }

    private fun writeAlarms(alarms: List<Alarm>) {
        // commit() is deliberately synchronous: persist an occurrence before scheduling it.
        val saved = preferences.edit().putString("data", encodeAlarms(alarms)).commit()
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
                .putString("data", encodeAlarms(migration.alarms))
                .putString("barcodes", encodeBarcodes(migration.barcodes))
                .putBoolean("barcode_library_migrated", true)
                .commit()
        check(saved) { "Could not migrate barcode library" }
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
