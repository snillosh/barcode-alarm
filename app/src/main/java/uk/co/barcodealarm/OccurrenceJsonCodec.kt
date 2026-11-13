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
                snapshot = AlarmJsonCodec.decode(json.getString("snapshot")).single(),
                stage = OccurrenceStage.valueOf(json.getString("stage")),
                eligibleAt = json.optLong("eligible"),
                deadlineAt = json.optLong("deadline"),
                eligibleElapsed = json.optLong("elapsed"),
                firstScanAt = json.optLong("first"),
                verificationConsumed = json.optBoolean("consumed"),
                resumeStage = OccurrenceStage.valueOf(json.optString("resume", "INITIAL")),
                fullVolume = json.optBoolean("full"),
                snoozeAt = json.optLong("snoozeAt"),
            )
        }
    }

    fun encode(values: List<Occurrence>): String {
        val array = JSONArray()
        values
            .filter { it.stage != OccurrenceStage.COMPLETED }
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
                    }
                )
            }
        return array.toString()
    }
}
