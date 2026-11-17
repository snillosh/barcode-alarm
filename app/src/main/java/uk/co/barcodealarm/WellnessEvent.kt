package uk.co.barcodealarm

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Raw completion only: no barcode, label, URI, or inferred waking time leaves the device. */
data class WellnessEvent(
    val eventId: String,
    val installationId: String,
    val occurrenceId: Int,
    val definitionId: Int,
    val occurredAt: Long,
    val scheduledAt: Long?,
    val zoneId: String?,
    val offsetSeconds: Int,
) {
    fun json(): JSONObject =
        JSONObject().apply {
            put("eventId", eventId)
            put("sourceApplication", "barcode-alarm")
            put("installationId", installationId)
            put("eventType", "alarm.occurrence.completed")
            put("schemaVersion", 1)
            put("occurredAt", Instant.ofEpochMilli(occurredAt).toString())
            zoneId?.let { put("zoneId", it) }
            put("offsetSeconds", offsetSeconds)
            put(
                "payload",
                JSONObject().apply {
                    put("occurrenceId", occurrenceId)
                    put("definitionId", definitionId)
                    scheduledAt?.let { put("scheduledAt", Instant.ofEpochMilli(it).toString()) }
                },
            )
        }

    companion object {
        fun capture(
            occurrence: Occurrence,
            installation: String,
            now: Long,
            zone: ZoneId,
        ): WellnessEvent =
            WellnessEvent(
                UUID.randomUUID().toString(),
                installation,
                occurrence.id,
                occurrence.definitionId,
                now,
                occurrence.snapshot.occurrenceScheduledAtMillis.takeIf { it > 0 },
                zone.id.takeIf { it in ZoneId.getAvailableZoneIds() },
                zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds,
            )

        fun decode(json: JSONObject): WellnessEvent {
            val payload = json.getJSONObject("payload")
            return WellnessEvent(
                json.getString("eventId"),
                json.getString("installationId"),
                payload.getInt("occurrenceId"),
                payload.getInt("definitionId"),
                Instant.parse(json.getString("occurredAt")).toEpochMilli(),
                if (payload.has("scheduledAt"))
                    Instant.parse(payload.getString("scheduledAt")).toEpochMilli()
                else null,
                if (json.has("zoneId")) json.getString("zoneId") else null,
                json.getInt("offsetSeconds"),
            )
        }
    }
}

data class WellnessRecord(
    val event: WellnessEvent,
    val acknowledgedBy: Map<String, Long> = emptyMap(),
    val rejectedBy: Map<String, String> = emptyMap(),
    // Only this short-lived recovery marker contains a local alarm snapshot. Never uploaded.
    val recovery: Occurrence? = null,
)

object WellnessHistoryCodec {
    fun encode(records: List<WellnessRecord>): String =
        JSONArray()
            .apply {
                records.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("event", record.event.json())
                            put("acknowledged", JSONObject(record.acknowledgedBy))
                            put("rejected", JSONObject(record.rejectedBy))
                            record.recovery?.let {
                                put(
                                    "recovery",
                                    OccurrenceJsonCodec.encode(listOf(it), includeTerminal = true),
                                )
                            }
                        }
                    )
                }
            }
            .toString()

    fun decode(data: String): List<WellnessRecord> {
        val array = JSONArray(data)
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val ack = json.getJSONObject("acknowledged")
            val rejected = json.getJSONObject("rejected")
            WellnessRecord(
                WellnessEvent.decode(json.getJSONObject("event")),
                ack.keys().asSequence().associateWith { ack.getLong(it) },
                rejected.keys().asSequence().associateWith { rejected.getString(it) },
                if (json.has("recovery"))
                    OccurrenceJsonCodec.decode(json.getString("recovery")).single()
                else null,
            )
        }
    }
}
