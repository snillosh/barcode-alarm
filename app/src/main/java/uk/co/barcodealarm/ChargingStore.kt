package uk.co.barcodealarm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** Charging capture/queue is independent of the alarm transition transaction. */
class ChargingStore(context: Context) : SQLiteOpenHelper(context, "charging.db", null, 1) {
    // Resolve outside SQLite transactions: sync acknowledgements take the occurrence lock first.
    private val appInstallation = OccurrenceStore(context).installationId()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (slot INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, body TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE delivery (id TEXT NOT NULL, server TEXT NOT NULL, rejected INTEGER NOT NULL, PRIMARY KEY(id,server))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun state(): JSONObject? =
        readableDatabase.rawQuery("SELECT body FROM state WHERE slot=1", null).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }

    private fun save(state: JSONObject) {
        writableDatabase.insertWithOnConflict(
            "state",
            null,
            ContentValues().apply {
                put("slot", 1)
                put("body", state.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun installation(): String = appInstallation

    fun begin(window: BedtimeWindow, now: Long, powered: Boolean, elapsed: Long) {
        // Every process restart breaks continuity. Never manufacture an edge from a snapshot.
        val previous = state()
        save(
            JSONObject().apply {
                put("night", window.night.toString())
                put("zone", window.zone.id)
                put(
                    "continuous",
                    (previous == null || previous.optString("night") != window.night.toString()) &&
                        now >= window.start &&
                        now - window.start <= 10_000,
                )
                put("powered", powered)
                put("lastElapsed", elapsed)
                put("lastWall", now)
                put("finished", false)
            }
        )
    }

    private fun validClock(state: JSONObject, now: Long, elapsed: Long): Boolean =
        elapsed >= state.getLong("lastElapsed") &&
            kotlin.math.abs(
                (now - state.getLong("lastWall")) - (elapsed - state.getLong("lastElapsed"))
            ) < 5_000

    fun invalidate() {
        state()?.let {
            it.put("continuous", false)
            save(it)
        }
    }

    fun observe(now: Long, elapsed: Long, powered: Boolean, battery: Int?, charger: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val state = state() ?: return
            val window =
                BedtimeWindow(
                    LocalDate.parse(state.getString("night")),
                    ZoneId.of(state.getString("zone")),
                )
            if (!window.contains(now) || state.optBoolean("finished")) return
            if (!validClock(state, now, elapsed) || ZoneId.systemDefault() != window.zone)
                state.put("continuous", false)
            if (state.getBoolean("powered") != powered) {
                val id = UUID.randomUUID().toString()
                val payload = windowPayload(window).put("chargerType", charger)
                battery?.let { payload.put("batteryPercent", it) }
                insert(
                    envelope(
                        id,
                        if (powered) "phone.power.connected" else "phone.power.disconnected",
                        now,
                        window,
                        payload,
                    )
                )
                state.put("powered", powered)
                if (powered) {
                    state.put("connection", now)
                    state.put("connectionId", id)
                } else {
                    state.remove("connection")
                    state.remove("connectionId")
                }
            }
            state.put("lastElapsed", elapsed)
            state.put("lastWall", now)
            save(state)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finish(now: Long, elapsed: Long, poweredNow: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val state = state() ?: return
            if (state.optBoolean("finished")) return
            val window =
                BedtimeWindow(
                    LocalDate.parse(state.getString("night")),
                    ZoneId.of(state.getString("zone")),
                )
            // A late boundary callback cannot prove the power state at 03:00.
            val continuous =
                state.getBoolean("continuous") &&
                    validClock(state, now, elapsed) &&
                    now >= window.end &&
                    now - window.end <= 10_000 &&
                    ZoneId.systemDefault() == window.zone
            val connection = if (state.has("connection")) state.getLong("connection") else null
            ChargingRules.estimate(
                    continuous,
                    poweredNow && state.getBoolean("powered"),
                    connection,
                    window,
                )
                ?.let {
                    val id =
                        UUID.nameUUIDFromBytes(
                                (installation() + ":bedtime:v1:" + window.start).toByteArray()
                            )
                            .toString()
                    val payload =
                        windowPayload(window)
                            .put("estimatedAt", Instant.ofEpochMilli(it).toString())
                            .put("sourceConnectionEventId", state.getString("connectionId"))
                            .put("algorithmVersion", 1)
                    insert(envelope(id, "sleep.bedtime.estimated", window.end, window, payload))
                }
            state.put("finished", true)
            save(state)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearWindow() {
        writableDatabase.delete("state", null, null)
    }

    private fun windowPayload(window: BedtimeWindow) =
        JSONObject()
            .put("night", window.night.toString())
            .put("windowStart", Instant.ofEpochMilli(window.start).toString())
            .put("windowEnd", Instant.ofEpochMilli(window.end).toString())

    private fun envelope(
        id: String,
        type: String,
        at: Long,
        window: BedtimeWindow,
        payload: JSONObject,
    ) =
        JSONObject()
            .put("eventId", id)
            .put("installationId", installation())
            .put("sourceApplication", "barcode-alarm")
            .put("eventType", type)
            .put("schemaVersion", 1)
            .put("occurredAt", Instant.ofEpochMilli(at).toString())
            .put("zoneId", window.zone.id)
            .put(
                "offsetSeconds",
                window.zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds,
            )
            .put("payload", payload)

    private fun insert(event: JSONObject) {
        writableDatabase.insertWithOnConflict(
            "events",
            null,
            ContentValues().apply {
                put("id", event.getString("eventId"))
                put("body", event.toString())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun pending(server: String): List<ChargingUpload> =
        readableDatabase
            .rawQuery(
                "SELECT e.id,e.body FROM events e LEFT JOIN delivery d ON d.id=e.id AND d.server=? WHERE d.id IS NULL ORDER BY e.sequence LIMIT 10",
                arrayOf(server),
            )
            .use {
                buildList {
                    while (it.moveToNext()) add(ChargingUpload(it.getString(0), it.getString(1)))
                }
            }

    fun delivered(id: String, server: String, rejected: Boolean) {
        writableDatabase.insertWithOnConflict(
            "delivery",
            null,
            ContentValues().apply {
                put("id", id)
                put("server", server)
                put("rejected", if (rejected) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun retry(server: String) {
        writableDatabase.delete("delivery", "server=? AND rejected=1", arrayOf(server))
    }

    fun counts(server: String): Pair<Int, Int> =
        readableDatabase
            .rawQuery(
                "SELECT sum(CASE WHEN d.id IS NULL OR d.rejected=1 THEN 1 ELSE 0 END),sum(CASE WHEN d.rejected=1 THEN 1 ELSE 0 END) FROM events e LEFT JOIN delivery d ON d.id=e.id AND d.server=?",
                arrayOf(server),
            )
            .use {
                it.moveToFirst()
                it.getInt(0) to it.getInt(1)
            }
}
