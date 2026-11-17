package uk.co.barcodealarm

import android.app.Application
import android.content.Context
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WellnessSyncTest {
    private lateinit var context: Context
    private lateinit var settings: WellnessSettings
    private lateinit var store: OccurrenceStore

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("occurrences", 0).edit().clear().commit()
        context.getSharedPreferences("wellness_sync", 0).edit().clear().commit()
        settings = WellnessSettings(context)
        settings.save(WellnessConnection.DEFAULT_SERVER, null, true)
        store = OccurrenceStore(context)
        val id =
            store.beginCompletion(
                Occurrence(
                    1000000,
                    1,
                    Alarm(1000000, 7, 0, "", ""),
                    stage = OccurrenceStage.COMPLETED,
                )
            )
        store.settleCompletion(id)
    }

    private fun synchronizer(
        request: (String, String, UploadEvent?) -> WellnessHttpClient.Response
    ) = WellnessSynchronizer(context, request, { "test-only-fake-credential" })

    private fun ack(event: UploadEvent, code: Int = 201) =
        WellnessHttpClient.Response(
            code,
            """{"eventId":"${event.eventId}","status":"${if (code == 201) "accepted" else "duplicate"}","receivedAt":"2026-11-17T07:00:00Z"}""",
        )

    @Test
    fun offlineFailureAndRestartKeepSameIdUntilAcknowledged() {
        val id = store.history().single().event.eventId
        assertEquals(
            SyncOutcome.RETRY,
            synchronizer { _, _, _ -> throw IOException("private-error") }.run(),
        )
        assertEquals(id, OccurrenceStore(context).history().single().event.eventId)
        assertFalse(settings.status().contains("private-error"))
        assertEquals(SyncOutcome.SUCCESS, synchronizer { _, _, event -> ack(event!!) }.run())
        assertTrue(store.pending(WellnessConnection.DEFAULT_SERVER).isEmpty())
        assertTrue(settings.lastSuccess() > 0)
    }

    @Test
    fun lostResponseCanBeRetriedAsBackendDuplicate() {
        val id = store.history().single().event.eventId
        synchronizer { _, _, _ -> throw IOException("response lost after server accepted") }.run()
        assertEquals(
            SyncOutcome.SUCCESS,
            synchronizer { _, _, event ->
                    assertEquals(id, event!!.eventId)
                    ack(event, 200)
                }
                .run(),
        )
        assertTrue(store.pending(WellnessConnection.DEFAULT_SERVER).isEmpty())
    }

    @Test
    fun rejectedCredentialPausesUntilSettingsChange() {
        var calls = 0
        val runner = synchronizer { _, _, _ ->
            calls++
            WellnessHttpClient.Response(401, "private-error")
        }
        assertEquals(SyncOutcome.SUCCESS, runner.run())
        assertEquals(SyncOutcome.SUCCESS, runner.run())
        assertEquals(1, calls)
        assertEquals(1, store.pending(WellnessConnection.DEFAULT_SERVER).size)
        settings.save(WellnessConnection.DEFAULT_SERVER, null, true)
        assertEquals(SyncOutcome.SUCCESS, synchronizer { _, _, event -> ack(event!!) }.run())
    }

    @Test
    fun disabledSyncNeverReadsCredentialOrCallsNetwork() {
        settings.save(WellnessConnection.DEFAULT_SERVER, null, false)
        val runner =
            WellnessSynchronizer(
                context,
                { _, _, _ -> error("network must not run") },
                { error("key must not be read") },
            )
        assertEquals(SyncOutcome.SUCCESS, runner.run())
        assertEquals(1, store.history().size)
    }

    @Test
    fun disablingOrChangingDestinationDuringRequestNeverAcknowledgesStaleConfiguration() {
        assertEquals(
            SyncOutcome.SUCCESS,
            synchronizer { _, _, event ->
                    settings.save("https://new.example.com", null, true)
                    ack(event!!)
                }
                .run(),
        )
        assertTrue(store.history().single().acknowledgedBy.isEmpty())
        assertEquals(
            SyncOutcome.SUCCESS,
            synchronizer { server, _, event ->
                    assertEquals("https://new.example.com", server)
                    ack(event!!)
                }
                .run(),
        )
        assertFalse(
            store.history().single().acknowledgedBy.containsKey(WellnessConnection.DEFAULT_SERVER)
        )
        assertTrue(store.history().single().acknowledgedBy.containsKey("https://new.example.com"))
    }

    @Test
    fun disablingWhileUploadIsInFlightLeavesRecordPendingForSameIdRetry() {
        val id = store.history().single().event.eventId
        synchronizer { _, _, event ->
                settings.save(WellnessConnection.DEFAULT_SERVER, null, false)
                ack(event!!)
            }
            .run()
        assertTrue(store.history().single().acknowledgedBy.isEmpty())
        settings.save(WellnessConnection.DEFAULT_SERVER, null, true)
        synchronizer { _, _, event ->
                assertEquals(id, event!!.eventId)
                ack(event, 200)
            }
            .run()
        assertTrue(store.pending(WellnessConnection.DEFAULT_SERVER).isEmpty())
    }

    @Test
    fun permanentRecordErrorsAreRetainedAndManualRetryKeepsId() {
        val id = store.history().single().event.eventId
        synchronizer { _, _, _ -> WellnessHttpClient.Response(409, "private-error") }.run()
        assertTrue(store.pending(WellnessConnection.DEFAULT_SERVER).isEmpty())
        assertTrue(
            store.history().single().rejectedBy.containsKey(WellnessConnection.DEFAULT_SERVER)
        )
        assertFalse(settings.status().contains("private-error"))
        store.retryRejected(WellnessConnection.DEFAULT_SERVER)
        assertEquals(id, store.pending(WellnessConnection.DEFAULT_SERVER).single().event.eventId)
    }

    @Test
    fun rateLimitingHonorsRetryAfterWithoutSleepingOrDeletingRecords() {
        val before = System.currentTimeMillis()
        var calls = 0
        val runner = synchronizer { _, _, _ ->
            calls++
            WellnessHttpClient.Response(429, "", 60)
        }
        assertEquals(SyncOutcome.RETRY, runner.run())
        assertTrue(settings.retryAt() >= before + 60_000)
        assertEquals(SyncOutcome.RETRY, runner.run())
        assertEquals(1, calls)
        assertEquals(1, store.pending(WellnessConnection.DEFAULT_SERVER).size)
    }

    @Test
    fun serverFailureAndBadAcknowledgementNeverMarkAnEventDelivered() {
        assertEquals(
            SyncOutcome.RETRY,
            synchronizer { _, _, _ -> WellnessHttpClient.Response(503, "") }.run(),
        )
        settings.manualRetry()
        assertEquals(
            SyncOutcome.RETRY,
            synchronizer { _, _, _ ->
                    WellnessHttpClient.Response(
                        201,
                        """{"eventId":"wrong","status":"accepted","receivedAt":"2026-11-17T07:00:00Z"}""",
                    )
                }
                .run(),
        )
        assertTrue(store.history().single().acknowledgedBy.isEmpty())
    }

    @Test
    fun explicitConnectionTestWorksWithBackgroundSyncDisabledAndChecksPermission() {
        settings.save(WellnessConnection.DEFAULT_SERVER, null, false)
        assertEquals(
            SyncOutcome.SUCCESS,
            synchronizer { _, _, event ->
                    assertNull(event)
                    WellnessHttpClient.Response(
                        200,
                        """{"application":"barcode-alarm","permissions":["alarm:write"],"supportedEvents":{"alarm.occurrence.completed":[1]}}""",
                    )
                }
                .run(testing = true),
        )
        assertTrue(settings.status().contains("Connection verified"))
        assertEquals(0L, settings.lastSuccess())
    }

    @Test
    fun connectionWithoutUploadPermissionPausesRatherThanRetryingForever() {
        assertEquals(
            SyncOutcome.SUCCESS,
            synchronizer { _, _, _ ->
                    WellnessHttpClient.Response(
                        200,
                        """{"application":"barcode-alarm","permissions":["connection:test"],"supportedEvents":{}}""",
                    )
                }
                .run(testing = true),
        )
        assertTrue(settings.blocked(settings.configuration()))
        assertTrue(settings.status().contains("upload permission"))
        assertEquals(1, store.history().size)
    }

    @Test
    fun missingOrUnreadableCredentialPausesWithoutDroppingHistory() {
        assertEquals(
            SyncOutcome.SUCCESS,
            WellnessSynchronizer(context, { _, _, _ -> error("network") }, { null }).run(),
        )
        assertTrue(settings.blocked(settings.configuration()))
        settings.manualRetry()
        assertEquals(
            SyncOutcome.SUCCESS,
            WellnessSynchronizer(
                    context,
                    { _, _, _ -> error("network") },
                    { error("key unavailable") },
                )
                .run(),
        )
        assertTrue(settings.status().contains("Replace"))
        assertEquals(1, store.history().size)
    }
}
