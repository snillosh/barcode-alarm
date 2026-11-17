package uk.co.barcodealarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

enum class SyncOutcome {
    SUCCESS,
    RETRY,
}

class WellnessSynchronizer(
    private val context: Context,
    private val request: (String, String, UploadEvent?) -> WellnessHttpClient.Response =
        WellnessHttpClient()::request,
    private val readCredential: () -> String? = WellnessSettings(context)::credential,
    private val stopped: () -> Boolean = { false },
) {
    fun run(testing: Boolean = false): SyncOutcome {
        val settings = WellnessSettings(context)
        val config = settings.configuration()
        if ((!config.enabled && !testing) || (!testing && settings.blocked(config)))
            return SyncOutcome.SUCCESS
        if (System.currentTimeMillis() < settings.retryAt()) return SyncOutcome.RETRY
        try {
            OccurrenceManager(context).recoverCompletions()
            val credential =
                try {
                    readCredential()
                } catch (_: Exception) {
                    settings.status(config, "Replace the unreadable saved API key", blocked = true)
                    return SyncOutcome.SUCCESS
                }
            if (credential == null) {
                settings.status(config, "Add an API key to synchronise", blocked = true)
                return SyncOutcome.SUCCESS
            }
            if (testing) {
                if (!current(settings, config, testing)) return SyncOutcome.SUCCESS
                val response = request(config.serverUrl, credential, null)
                if (response.code != 200) return handle(response, settings, config)
                val body = JSONObject(response.body)
                val permissions = body.optJSONArray("permissions")
                val scopes =
                    if (permissions == null) emptyList()
                    else (0 until permissions.length()).map { permissions.optString(it) }
                val schemas =
                    body
                        .optJSONObject("supportedEvents")
                        ?.optJSONArray("alarm.occurrence.completed")
                val compatible =
                    body.optString("application") == "barcode-alarm" &&
                        "alarm:write" in scopes &&
                        schemas != null &&
                        (0 until schemas.length()).any { schemas.optInt(it) == 1 }
                if (!compatible) {
                    settings.status(
                        config,
                        "Connection reached; check app identity, upload permission and server version",
                        blocked = true,
                    )
                    return SyncOutcome.SUCCESS
                }
                settings.status(config, "Connection verified; key can upload alarm completions")
                return SyncOutcome.SUCCESS
            }
            val history = OccurrenceStore(context)
            val records = history.pending(config.serverUrl).take(10)
            var chargingPending = false
            val charging = ChargingStore(context)
            try {
                for (event in charging.pending(config.serverUrl)) {
                    if (!current(settings, config, false)) return SyncOutcome.SUCCESS
                    val response = request(config.serverUrl, credential, event)
                    if (!current(settings, config, false)) return SyncOutcome.SUCCESS
                    if (
                        WellnessConnection.acknowledged(response.code, response.body, event.eventId)
                    ) {
                        synchronized(OccurrenceStore.lock) {
                            if (current(settings, config, false)) {
                                charging.delivered(event.eventId, config.serverUrl, false)
                                settings.status(
                                    config,
                                    "Synchronised successfully",
                                    acknowledged = true,
                                )
                            }
                        }
                    } else if (response.code in setOf(400, 409, 413, 415, 422)) {
                        charging.delivered(event.eventId, config.serverUrl, true)
                        settings.status(config, "Charging records rejected; retained for review")
                    } else return handle(response, settings, config)
                }
                chargingPending = charging.pending(config.serverUrl).isNotEmpty()
            } finally {
                charging.close()
            }
            if (records.isEmpty()) {
                if (chargingPending) return SyncOutcome.RETRY
                settings.status(config, "No pending records (rejected records need manual retry)")
                return SyncOutcome.SUCCESS
            }
            for (record in records) {
                if (!current(settings, config, false)) return SyncOutcome.SUCCESS
                val response = request(config.serverUrl, credential, record.event)
                if (!current(settings, config, false)) return SyncOutcome.SUCCESS
                if (
                    WellnessConnection.acknowledged(
                        response.code,
                        response.body,
                        record.event.eventId,
                    )
                ) {
                    synchronized(OccurrenceStore.lock) {
                        if (current(settings, config, false)) {
                            history.acknowledge(
                                record.event.eventId,
                                config.serverUrl,
                                System.currentTimeMillis(),
                            )
                            settings.status(
                                config,
                                "Synchronised successfully",
                                acknowledged = true,
                            )
                        }
                    }
                } else if (response.code in setOf(400, 409, 413, 415, 422)) {
                    history.reject(
                        record.event.eventId,
                        config.serverUrl,
                        "Server rejected record (${response.code})",
                    )
                    settings.status(config, "Some records were rejected and retained for review")
                } else return handle(response, settings, config)
            }
            return if (chargingPending || history.pending(config.serverUrl).isNotEmpty())
                SyncOutcome.RETRY
            else SyncOutcome.SUCCESS
        } catch (_: Exception) {
            try {
                settings.status(config, "Sync unavailable; records remain on this device")
            } catch (_: Exception) {}
            return SyncOutcome.RETRY
        }
    }

    private fun current(
        settings: WellnessSettings,
        original: WellnessConfiguration,
        testing: Boolean,
    ): Boolean {
        val current = settings.configuration()
        return !stopped() && current.revision == original.revision && (testing || current.enabled)
    }

    private fun handle(
        response: WellnessHttpClient.Response,
        settings: WellnessSettings,
        config: WellnessConfiguration,
    ): SyncOutcome {
        if (response.code == 401 || response.code == 403) {
            settings.status(
                config,
                "API key rejected; replace it or check permissions",
                blocked = true,
            )
            return SyncOutcome.SUCCESS
        }
        if (response.code in 300..399 || response.code == 404) {
            settings.status(
                config,
                "Check the server URL; redirects are not followed",
                blocked = true,
            )
            return SyncOutcome.SUCCESS
        }
        val delay = maxOf(30, response.retryAfterSeconds)
        settings.status(
            config,
            "Server unavailable or unexpected response; will retry",
            retryAt = System.currentTimeMillis() + delay * 1000,
        )
        return SyncOutcome.RETRY
    }
}

class WellnessSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        private val networkLock = java.util.concurrent.locks.ReentrantLock()
    }

    override fun doWork(): Result {
        if (!networkLock.tryLock()) return Result.retry()
        return try {
            when (
                WellnessSynchronizer(applicationContext, stopped = { isStopped })
                    .run(inputData.getBoolean("test", false))
            ) {
                SyncOutcome.SUCCESS -> Result.success()
                SyncOutcome.RETRY -> Result.retry()
            }
        } finally {
            networkLock.unlock()
        }
    }
}
