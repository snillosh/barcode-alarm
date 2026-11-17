package uk.co.barcodealarm

import android.content.Context
import java.time.ZoneId
import java.util.UUID

class OccurrenceStore(private val context: Context) : CompletionStorage {
    companion object {
        // Worker acknowledgements and UI/receiver transitions share one process-wide lock.
        val lock = Any()
    }

    private val prefs = context.getSharedPreferences("occurrences", Context.MODE_PRIVATE)

    fun all(): List<Occurrence> =
        synchronized(lock) { OccurrenceJsonCodec.decode(prefs.getString("data", "[]") ?: "[]") }

    fun get(id: Int) = all().find { it.id == id }

    fun create(alarm: Alarm): Occurrence =
        synchronized(lock) {
            val sourceTime =
                if (alarm.scheduledAtMillis > 0) alarm.scheduledAtMillis
                else alarm.occurrenceScheduledAtMillis
            all()
                .find {
                    it.source == OccurrenceSource.ALARM &&
                        it.definitionId == alarm.id &&
                        it.snapshot.occurrenceScheduledAtMillis == sourceTime
                }
                ?.let {
                    return@synchronized it
                }
            val codes = AlarmStore(context).getBarcodes()
            val id = nextId()
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
                            firstBarcodeName =
                                codes.find { it.id == alarm.barcodeId }?.name.orEmpty(),
                            secondBarcodeName =
                                codes.find { it.id == alarm.secondBarcodeId }?.name.orEmpty(),
                        ),
                )
            save(occurrence)
            occurrence
        }

    fun nextId(): Int =
        synchronized(lock) {
            val id = prefs.getInt("next", 1_000_000)
            check(id < Int.MAX_VALUE && prefs.edit().putInt("next", id + 1).commit()) {
                "Could not reserve occurrence ID"
            }
            id
        }

    fun remove(id: Int) =
        synchronized(lock) {
            check(
                prefs
                    .edit()
                    .putString("data", OccurrenceJsonCodec.encode(all().filterNot { it.id == id }))
                    .commit()
            )
        }

    override fun save(value: Occurrence) =
        synchronized(lock) {
            check(prefs.edit().putString("data", updated(value)).commit()) {
                "Could not persist occurrence"
            }
        }

    private fun updated(value: Occurrence): String {
        val values =
            all()
                .filterNot {
                    value.source == OccurrenceSource.TIMER &&
                        it.source == value.source &&
                        it.definitionId == value.definitionId &&
                        it.id != value.id &&
                        it.terminal
                }
                .toMutableList()
        val index = values.indexOfFirst { it.id == value.id }
        if (
            index >= 0 &&
                values[index].stage == OccurrenceStage.COUNTDOWN &&
                value.stage == OccurrenceStage.INITIAL
        ) {
            // Append newly expired timers behind occurrences already ringing.
            values.removeAt(index)
            values.add(value)
        } else if (index < 0) values.add(value) else values[index] = value
        return OccurrenceJsonCodec.encode(values)
    }

    fun history(): List<WellnessRecord> =
        synchronized(lock) {
            WellnessHistoryCodec.decode(prefs.getString("wellness_history", "[]") ?: "[]")
        }

    override fun beginCompletion(next: Occurrence): String =
        synchronized(lock) {
            val installation =
                prefs.getString("wellness_installation", null) ?: UUID.randomUUID().toString()
            val event =
                WellnessEvent.capture(
                    next,
                    installation,
                    System.currentTimeMillis(),
                    ZoneId.systemDefault(),
                )
            val server = WellnessSettings(context).configuration().serverUrl
            val records = history()
            val retained =
                records.filter { !it.acknowledgedBy.containsKey(server) || it.recovery != null } +
                    records
                        .filter { it.acknowledgedBy.containsKey(server) && it.recovery == null }
                        .takeLast(1000)
            check(retained.size < 10_000) { "Local history capacity reached" }
            check(
                prefs
                    .edit()
                    .putString("data", updated(next))
                    .putString("wellness_installation", installation)
                    .putString(
                        "wellness_history",
                        WellnessHistoryCodec.encode(
                            retained + WellnessRecord(event, recovery = next)
                        ),
                    )
                    .commit()
            ) {
                "Could not capture completion"
            }
            event.eventId
        }

    override fun settleCompletion(eventId: String) =
        synchronized(lock) {
            writeHistory(
                history().map { if (it.event.eventId == eventId) it.copy(recovery = null) else it }
            )
        }

    override fun rollbackCompletion(previous: Occurrence, eventId: String) =
        synchronized(lock) {
            check(
                prefs
                    .edit()
                    .putString("data", updated(previous))
                    .putString(
                        "wellness_history",
                        WellnessHistoryCodec.encode(
                            history().filterNot { it.event.eventId == eventId }
                        ),
                    )
                    .commit()
            ) {
                "Could not restore occurrence"
            }
        }

    override fun reportCaptureFailure() {
        // No exception details/credentials in diagnostics, and no new failure on the alarm path.
        try {
            prefs.edit().putBoolean("wellness_capture_error", true).commit()
        } catch (_: Exception) {}
    }

    fun hasCaptureWarning(): Boolean = prefs.getBoolean("wellness_capture_error", false)

    fun acknowledge(eventId: String, server: String, now: Long) =
        synchronized(lock) {
            writeHistory(
                history().map {
                    if (it.event.eventId == eventId && it.recovery == null)
                        it.copy(
                            acknowledgedBy = it.acknowledgedBy + (server to now),
                            rejectedBy = it.rejectedBy - server,
                        )
                    else it
                }
            )
        }

    fun reject(eventId: String, server: String, reason: String) =
        synchronized(lock) {
            writeHistory(
                history().map {
                    if (it.event.eventId == eventId)
                        it.copy(rejectedBy = it.rejectedBy + (server to reason))
                    else it
                }
            )
        }

    fun retryRejected(server: String) =
        synchronized(lock) {
            writeHistory(history().map { it.copy(rejectedBy = it.rejectedBy - server) })
        }

    fun pending(server: String): List<WellnessRecord> =
        history().filter {
            it.recovery == null &&
                !it.acknowledgedBy.containsKey(server) &&
                !it.rejectedBy.containsKey(server)
        }

    private fun writeHistory(records: List<WellnessRecord>) {
        check(
            prefs
                .edit()
                .putString("wellness_history", WellnessHistoryCodec.encode(records))
                .commit()
        ) {
            "Could not persist local history"
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
