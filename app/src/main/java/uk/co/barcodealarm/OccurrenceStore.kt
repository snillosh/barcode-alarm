package uk.co.barcodealarm

import android.content.Context

class OccurrenceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("occurrences", Context.MODE_PRIVATE)

    fun all(): List<Occurrence> = OccurrenceJsonCodec.decode(prefs.getString("data", "[]") ?: "[]")

    fun get(id: Int) = all().find { it.id == id }

    fun create(alarm: Alarm): Occurrence {
        val sourceTime =
            if (alarm.scheduledAtMillis > 0) alarm.scheduledAtMillis
            else alarm.occurrenceScheduledAtMillis
        all()
            .find {
                it.definitionId == alarm.id && it.snapshot.occurrenceScheduledAtMillis == sourceTime
            }
            ?.let {
                return it
            }
        val codes = AlarmStore(context).getBarcodes()
        val id = prefs.getInt("next", 1_000_000)
        check(prefs.edit().putInt("next", id + 1).commit())
        val occurrence =
            Occurrence(
                id = id,
                definitionId = alarm.id,
                snapshot =
                    alarm.copy(
                        id = id,
                        isRinging = true,
                        scheduledAtMillis = 0,
                        occurrenceScheduledAtMillis = sourceTime,
                        playbackStartedAtElapsedMillis = -1,
                        firstBarcodeName = codes.find { it.id == alarm.barcodeId }?.name.orEmpty(),
                        secondBarcodeName =
                            codes.find { it.id == alarm.secondBarcodeId }?.name.orEmpty(),
                    ),
            )
        save(occurrence)
        return occurrence
    }

    fun save(value: Occurrence) {
        val values = all().toMutableList()
        val index = values.indexOfFirst { it.id == value.id }
        if (index < 0) values.add(value) else values[index] = value
        check(prefs.edit().putString("data", OccurrenceJsonCodec.encode(values)).commit()) {
            "Could not persist occurrence"
        }
    }
}

/** Existing audio/maths UI still operates on Alarm records, now backed by occurrence snapshots. */
class ActiveAlarmStore(private val context: Context, private val audibleOnly: Boolean = false) {
    private val occurrences = OccurrenceStore(context)

    fun getAlarms() =
        occurrences.all().filter { if (audibleOnly) it.audible else it.active }.map { it.snapshot }

    fun saveAlarm(alarm: Alarm) {
        occurrences.get(alarm.id)?.let { occurrences.save(it.copy(snapshot = alarm)) }
    }

    fun recordPlaybackStart(id: Int, start: Long) {
        occurrences.get(id)?.let {
            saveAlarm(it.snapshot.copy(playbackStartedAtElapsedMillis = start))
        }
    }

    fun getBarcodes() = AlarmStore(context).getBarcodes()

    fun log(message: String) = AlarmStore(context).log(message)
}
