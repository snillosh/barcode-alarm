package uk.co.barcodealarm

import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject

/** Backward-compatible JSON mapping, independent of Android storage and lifecycle code. */
object AlarmJsonCodec {
    fun decode(jsonText: String, defaultCanBeSnoozed: Boolean = true): List<Alarm> {
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
                    label = AlarmOverview.cleanLabel(json.optString("label")),
                    alarmProtectionMinutes =
                        json.optInt("alarmProtectionMinutes", 5).coerceIn(1, 60),
                    canBeSnoozed = json.optBoolean("canBeSnoozed", defaultCanBeSnoozed),
                    soundUri = json.optString("sound"),
                    isEnabled = json.optBoolean("enabled"),
                    scheduledAtMillis = json.optLong("due"),
                    isRinging = json.optBoolean("ringing"),
                    mathsStartedAtElapsedMillis = json.optLong("started", -1),
                    correctAnswerCount = json.optInt("correct"),
                    barcodeId = json.optInt("barcodeId"),
                    firstBarcodeName = json.optString("firstName"),
                    secondBarcodeName = json.optString("secondName"),
                    secondBarcodeId = json.optInt("secondBarcodeId"),
                    secondBarcodeValue = json.optString("secondCode"),
                    secondBarcodeFormat = json.optString("secondFormat"),
                    scanIntervalMinutes = json.optInt("scanInterval", 1),
                    verificationEnabled = json.optBoolean("verificationEnabled"),
                    verificationDelayMinutes = json.optInt("verificationDelay", 5),
                    verificationResponseMinutes = json.optInt("verificationResponse", 2),
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
            json.put("label", AlarmOverview.cleanLabel(alarm.label))
            json.put("alarmProtectionMinutes", alarm.alarmProtectionMinutes)
            json.put("canBeSnoozed", alarm.canBeSnoozed)
            json.put("sound", alarm.soundUri)
            json.put("enabled", alarm.isEnabled)
            json.put("due", alarm.scheduledAtMillis)
            json.put("ringing", alarm.isRinging)
            json.put("started", alarm.mathsStartedAtElapsedMillis)
            json.put("correct", alarm.correctAnswerCount)
            json.put("barcodeId", alarm.barcodeId)
            json.put("firstName", alarm.firstBarcodeName)
            json.put("secondName", alarm.secondBarcodeName)
            json.put("secondBarcodeId", alarm.secondBarcodeId)
            json.put("secondCode", alarm.secondBarcodeValue)
            json.put("secondFormat", alarm.secondBarcodeFormat)
            json.put("scanInterval", alarm.scanIntervalMinutes)
            json.put("verificationEnabled", alarm.verificationEnabled)
            json.put("verificationDelay", alarm.verificationDelayMinutes)
            json.put("verificationResponse", alarm.verificationResponseMinutes)
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
