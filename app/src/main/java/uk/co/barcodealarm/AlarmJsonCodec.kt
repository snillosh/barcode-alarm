package uk.co.barcodealarm

import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject

/** Backward-compatible JSON mapping, independent of Android storage and lifecycle code. */
object AlarmJsonCodec {
    fun decode(jsonText: String): List<Alarm> {
        val jsonArray = JSONArray(jsonText)
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
                    repeatDays = decodeRepeatDays(json.optJSONArray("repeatDays")),
                    isSnoozed = json.optBoolean("snoozed"),
                    occurrenceScheduledAtMillis = json.optLong("occurrenceDue"),
                    playbackStartedAtElapsedMillis = json.optLong("playbackStarted", -1),
                )
            )
        }
        return alarms
    }

    fun decodeRepeatDays(days: JSONArray?): Set<DayOfWeek> {
        if (days == null) return emptySet()
        val selectedDays = mutableSetOf<DayOfWeek>()
        for (index in 0 until days.length()) {
            val isoDayNumber = days.optInt(index)
            if (isoDayNumber in 1..7) selectedDays.add(DayOfWeek.of(isoDayNumber))
        }
        return selectedDays
    }

    fun encode(alarms: List<Alarm>): String {
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
            json.put(
                "repeatDays",
                JSONArray(alarm.repeatDays.sortedBy { day -> day.value }.map { day -> day.value }),
            )
            json.put("snoozed", alarm.isSnoozed)
            json.put("occurrenceDue", alarm.occurrenceScheduledAtMillis)
            json.put("playbackStarted", alarm.playbackStartedAtElapsedMillis)
            jsonArray.put(json)
        }
        return jsonArray.toString()
    }
}
