package uk.co.barcodealarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Presets only. Runs live in the shared OccurrenceStore and use its global ID allocator. */
class TimerStore(context: Context) {
    private val preferences = context.getSharedPreferences("timers", Context.MODE_PRIVATE)

    fun all(): List<TimerPreset> =
        TimerJsonCodec.decode(preferences.getString("data", "[]").orEmpty())

    fun nextId(): Int {
        val id = maxOf(preferences.getInt("lastId", 0), all().maxOfOrNull { it.id } ?: 0) + 1
        check(id > 0 && preferences.edit().putInt("lastId", id).commit()) {
            "Could not reserve timer ID"
        }
        return id
    }

    fun save(preset: TimerPreset) {
        write(TimerRules.savePreset(all(), preset))
    }

    fun delete(id: Int) = write(TimerRules.deletePreset(all(), id))

    private fun write(presets: List<TimerPreset>) {
        check(preferences.edit().putString("data", TimerJsonCodec.encode(presets)).commit()) {
            "Could not save timers"
        }
    }
}

object TimerJsonCodec {
    fun encode(presets: List<TimerPreset>): String =
        JSONArray()
            .apply {
                presets.forEach { preset ->
                    put(
                        JSONObject().apply {
                            put("id", preset.id)
                            put("duration", preset.durationMillis)
                            put(
                                "configuration",
                                AlarmJsonCodec.encode(listOf(preset.configuration)),
                            )
                            put("reminderMinutes", preset.reminderMinutes)
                        }
                    )
                }
            }
            .toString()

    fun decode(data: String): List<TimerPreset> {
        val json = JSONArray(data)
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            TimerRules.validate(
                TimerPreset(
                    item.getInt("id"),
                    item.getLong("duration"),
                    AlarmJsonCodec.decode(item.getString("configuration")).single(),
                    item.optInt("reminderMinutes"),
                )
            )
        }
    }
}
