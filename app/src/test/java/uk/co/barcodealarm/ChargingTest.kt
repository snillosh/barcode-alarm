package uk.co.barcodealarm

import android.app.Application
import android.content.Context
import java.time.*
import java.util.TimeZone
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ChargingTest {
    private lateinit var context: Context
    private lateinit var store: ChargingStore
    private lateinit var original: TimeZone
    private val window = BedtimeWindow(LocalDate.of(2026, 11, 17), ZoneId.of("Europe/London"))
    private val server = WellnessConnection.DEFAULT_SERVER

    @Before
    fun before() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("charging.db")
        context.getSharedPreferences("wellness_sync", 0).edit().clear().commit()
        store = ChargingStore(context)
    }

    @After
    fun after() {
        store.close()
        context.deleteDatabase("charging.db")
        TimeZone.setDefault(original)
    }

    private fun begin(powered: Boolean = false) = store.begin(window, window.start, powered, 100000)

    private fun edge(hours: Long, powered: Boolean) {
        store.observe(
            window.start + hours * 3600000,
            100000 + hours * 3600000,
            powered,
            70,
            if (powered) "usb" else "unknown",
        )
    }

    private fun finish(powered: Boolean = true, delay: Long = 0) =
        store.finish(window.end + delay, 100000 + (window.end - window.start) + delay, powered)

    private fun events() = store.pending(server).map { it.json() }

    @Test
    fun windowUsesNightStartDateAndHandlesDst() {
        assertEquals(window, ChargingRules.window(window.start + 5 * 3600000, window.zone))
        assertTrue(window.contains(window.start))
        assertFalse(window.contains(window.end))
        assertEquals(
            8 * 3600000L,
            BedtimeWindow(LocalDate.of(2026, 10, 24), window.zone).let { it.end - it.start },
        )
        assertEquals(
            6 * 3600000L,
            BedtimeWindow(LocalDate.of(2026, 3, 28), window.zone).let { it.end - it.start },
        )
    }

    @Test
    fun connectionAndEstimateAreDurableAndDoNotDuplicateOnFinalization() {
        begin()
        edge(1, true)
        finish()
        finish()
        store.close()
        store = ChargingStore(context)
        val events = events()
        assertEquals(2, events.size)
        assertEquals("phone.power.connected", events[0].getString("eventType"))
        assertEquals(
            OccurrenceStore(context).installationId(),
            events[0].getString("installationId"),
        )
        assertEquals("sleep.bedtime.estimated", events[1].getString("eventType"))
        val estimate = events[1].getJSONObject("payload")
        assertEquals(events[0].getString("eventId"), estimate.getString("sourceConnectionEventId"))
        assertEquals(
            Instant.ofEpochMilli(window.start + 3600000).toString(),
            estimate.getString("estimatedAt"),
        )
        assertEquals(1, estimate.getInt("algorithmVersion"))
    }

    @Test
    fun alreadyChargingAtStartProducesNoManufacturedObservationOrEstimate() {
        begin(true)
        finish()
        assertTrue(events().isEmpty())
    }

    @Test
    fun unpluggedAtBoundaryProducesOnlyRawObservations() {
        begin()
        edge(1, true)
        edge(2, false)
        finish(false)
        assertEquals(
            listOf("phone.power.connected", "phone.power.disconnected"),
            events().map { it.getString("eventType") },
        )
    }

    @Test
    fun estimateUsesLastConnectionFollowingDisconnection() {
        begin()
        edge(1, true)
        edge(2, false)
        edge(3, true)
        finish()
        val events = events()
        assertEquals(4, events.size)
        assertEquals(
            events[2].getString("eventId"),
            events[3].getJSONObject("payload").getString("sourceConnectionEventId"),
        )
    }

    @Test
    fun restartSuppressesEstimateButRetainsRawHistory() {
        begin()
        edge(1, true)
        store.begin(window, window.start + 2 * 3600000, true, 100000 + 2 * 3600000)
        finish()
        assertEquals(1, events().size)
    }

    @Test
    fun lateBoundaryAndClockChangesCannotCreateEstimates() {
        begin()
        edge(1, true)
        finish(delay = 60000)
        assertEquals(1, events().size)
        context.deleteDatabase("unused")
        store.clearWindow()
        begin()
        store.observe(window.start + 3600000, 100001, true, 50, "usb")
        finish()
        assertEquals(2, events().size)
    }

    @Test
    fun captureOutsideWindowAndDuplicatePowerStatesAreIgnored() {
        begin()
        edge(1, true)
        edge(1, true)
        store.observe(window.end, 100000 + 7 * 3600000, false, 70, "unknown")
        assertEquals(1, events().size)
    }

    @Test
    fun laterNightCanTrackContinuouslyAfterPreviousFinishedNight() {
        begin()
        finish(false)
        val next = BedtimeWindow(window.night.plusDays(1), window.zone)
        store.begin(next, next.start, false, 200000)
        store.observe(next.start + 60000, 260000, true, 80, "wireless")
        store.finish(next.end, 200000 + (next.end - next.start), true)
        assertEquals(2, events().size)
    }

    @Test
    fun deliveryIsPerServerRejectedHistoryIsRetainedAndCanRetry() {
        begin()
        edge(1, true)
        val event = store.pending(server).single()
        store.delivered(event.eventId, server, true)
        assertTrue(store.pending(server).isEmpty())
        assertEquals(1, store.counts(server).second)
        assertEquals(1, store.pending("https://other.example.com").size)
        store.retry(server)
        assertEquals(event, store.pending(server).single())
        store.delivered(event.eventId, server, false)
        assertEquals(0, store.counts(server).first)
    }

    @Test
    fun offlineRetryUsesSameChargingEventAndDuplicateAckDrainsQueue() {
        begin()
        edge(1, true)
        WellnessSettings(context).save(server, null, true)
        val event = store.pending(server).single()
        val offline =
            WellnessSynchronizer(
                context,
                { _, _, _ -> throw java.io.IOException("offline") },
                { "test" },
            )
        assertEquals(SyncOutcome.RETRY, offline.run())
        assertEquals(event, store.pending(server).single())
        val online =
            WellnessSynchronizer(
                context,
                { _, _, row ->
                    assertEquals(event.eventId, row!!.eventId)
                    WellnessHttpClient.Response(
                        200,
                        """{"eventId":"${row.eventId}","status":"duplicate","receivedAt":"2026-11-18T03:00:00Z"}""",
                    )
                },
                { "test" },
            )
        assertEquals(SyncOutcome.SUCCESS, online.run())
        assertTrue(store.pending(server).isEmpty())
    }
}
