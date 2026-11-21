package uk.co.barcodealarm

import org.json.JSONArray
import org.json.JSONObject

/** Stable preferences mapping, kept pure so recovery can be verified without Android. */
object OccurrenceJsonCodec {
    fun decode(data: String): List<Occurrence> {
        val array = JSONArray(data)
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            Occurrence(
                id = json.getInt("id"),
                definitionId = json.getInt("definition"),
                snapshot =
                    AlarmJsonCodec.decode(
                            json.getString("snapshot"),
                            defaultCanBeSnoozed = json.optString("source", "ALARM") != "TIMER",
                        )
                        .single(),
                stage = OccurrenceStage.valueOf(json.getString("stage")),
                eligibleAt = json.optLong("eligible"),
                deadlineAt = json.optLong("deadline"),
                eligibleElapsed = json.optLong("elapsed"),
                firstScanAt = json.optLong("first"),
                verificationConsumed = json.optBoolean("consumed"),
                resumeStage = OccurrenceStage.valueOf(json.optString("resume", "INITIAL")),
                fullVolume = json.optBoolean("full"),
                snoozeAt = json.optLong("snoozeAt"),
                source = OccurrenceSource.valueOf(json.optString("source", "ALARM")),
                timerStartedAt = json.optLong("timerStartedAt"),
                timerStartedElapsed = json.optLong("timerStartedElapsed"),
                timerExpiresAt = json.optLong("timerExpiresAt"),
                timerExpiresElapsed = json.optLong("timerExpiresElapsed"),
                timerDurationMillis = json.optLong("timerDurationMillis"),
                reminderMinutes = json.optInt("reminderMinutes"),
                reminderIndex = json.optInt("reminderIndex"),
                timerBootCount = json.optInt("timerBootCount", -1),
                timerCancellationLockMinutes =
                    json.optInt("timerCancellationLockMinutes", 0).coerceIn(0, 60),
            )
        }
    }

    fun encode(values: List<Occurrence>, includeTerminal: Boolean = false): String {
        val array = JSONArray()
        values
            .filter { includeTerminal || !it.terminal || it.source == OccurrenceSource.TIMER }
            .forEach { occurrence ->
                array.put(
                    JSONObject().apply {
                        put("id", occurrence.id)
                        put("definition", occurrence.definitionId)
                        put("snapshot", AlarmJsonCodec.encode(listOf(occurrence.snapshot)))
                        put("stage", occurrence.stage.name)
                        put("eligible", occurrence.eligibleAt)
                        put("deadline", occurrence.deadlineAt)
                        put("elapsed", occurrence.eligibleElapsed)
                        put("first", occurrence.firstScanAt)
                        put("consumed", occurrence.verificationConsumed)
                        put("resume", occurrence.resumeStage.name)
                        put("full", occurrence.fullVolume)
                        put("snoozeAt", occurrence.snoozeAt)
                        put("source", occurrence.source.name)
                        put("timerStartedAt", occurrence.timerStartedAt)
                        put("timerStartedElapsed", occurrence.timerStartedElapsed)
                        put("timerExpiresAt", occurrence.timerExpiresAt)
                        put("timerExpiresElapsed", occurrence.timerExpiresElapsed)
                        put("timerDurationMillis", occurrence.timerDurationMillis)
                        put("reminderMinutes", occurrence.reminderMinutes)
                        put("reminderIndex", occurrence.reminderIndex)
                        put("timerBootCount", occurrence.timerBootCount)
                        put("timerCancellationLockMinutes", occurrence.timerCancellationLockMinutes)
                    }
                )
            }
        return array.toString()
    }
}
