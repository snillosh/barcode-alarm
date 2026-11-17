package uk.co.barcodealarm

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WellnessRulesTest {
    private val alarm =
        Alarm(
            1,
            7,
            0,
            "private-code",
            "QR_CODE",
            label = "private-label",
            occurrenceScheduledAtMillis = 1000,
        )
    private val occurrence = Occurrence(100, 1, alarm)

    @Test
    fun urlValidationRequiresHttpsAndNeverAcceptsEmbeddedCredentials() {
        assertEquals(
            "https://example.com",
            WellnessConnection.normalizeServer(" HTTPS://Example.com:443/ "),
        )
        assertEquals(
            "https://example.com/wellness",
            WellnessConnection.normalizeServer("https://example.com/wellness/"),
        )
        for (url in
            listOf(
                "http://example.com",
                "https://user:secret@example.com",
                "https://example.com?key=x",
                "https://example.com#fragment",
                "https://example.com:0",
                "https://example.com:70000",
                "https://example.com/a/../b",
                "https://example.com/%2fhidden",
                "not a URL",
            )) {
            try {
                WellnessConnection.normalizeServer(url)
                fail("Invalid URL accepted")
            } catch (_: Exception) {}
        }
    }

    @Test
    fun observationsKeepStableIdsAndZoneContextWithoutPrivateAlarmFields() {
        val before = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        val after = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        val event =
            WellnessEvent.capture(occurrence, "installation", before, ZoneId.of("Europe/London"))
        assertEquals(3600, event.offsetSeconds)
        assertEquals(
            0,
            WellnessEvent.capture(occurrence, "installation", after, ZoneId.of("Europe/London"))
                .offsetSeconds,
        )
        assertEquals(event, WellnessEvent.decode(event.json()))
        assertFalse(event.json().toString().contains("private"))
        assertFalse(event.json().toString().contains("barcodeValue"))
        val record =
            WellnessRecord(
                event,
                mapOf("https://example.com" to after),
                recovery = occurrence.copy(stage = OccurrenceStage.COMPLETED),
            )
        assertEquals(
            listOf(record),
            WellnessHistoryCodec.decode(WellnessHistoryCodec.encode(listOf(record))),
        )
    }

    @Test
    fun fixedOffsetZonesRemainUnambiguousWithoutInventingAnIanaName() {
        val event =
            WellnessEvent.capture(occurrence, "installation", 1000, java.time.ZoneOffset.ofHours(1))
        assertNull(event.zoneId)
        assertEquals(3600, event.offsetSeconds)
        assertFalse(event.json().has("zoneId"))
        assertEquals(event, WellnessEvent.decode(event.json()))
        val now = Instant.parse("2026-11-17T07:00:00Z")
        assertEquals(60L, WellnessConnection.retryAfter("60", now))
        assertEquals(60L, WellnessConnection.retryAfter("Tue, 17 Nov 2026 07:01:00 GMT", now))
    }

    @Test
    fun acknowledgementsMustMatchEventAndExpectedStatus() {
        val body = """{"eventId":"id","status":"accepted","receivedAt":"2026-11-17T07:00:00Z"}"""
        assertTrue(WellnessConnection.acknowledged(201, body, "id"))
        assertFalse(WellnessConnection.acknowledged(201, body, "other"))
        assertFalse(WellnessConnection.acknowledged(200, body, "id"))
        assertTrue(
            WellnessConnection.acknowledged(200, body.replace("accepted", "duplicate"), "id")
        )
        assertFalse(WellnessConnection.acknowledged(201, "{}", "id"))
    }

    private class MemoryJournal : CompletionStorage {
        var current: Occurrence? = null
        var pending = false
        var settled = false
        var warning = false
        var failBegin = false
        var failSettle = false

        override fun save(value: Occurrence) {
            current = value
        }

        override fun beginCompletion(next: Occurrence): String {
            if (failBegin) error("capture failed")
            current = next
            pending = true
            return "event"
        }

        override fun settleCompletion(eventId: String) {
            if (failSettle) error("settle failed")
            pending = false
            settled = true
        }

        override fun rollbackCompletion(previous: Occurrence, eventId: String) {
            current = previous
            pending = false
        }

        override fun reportCaptureFailure() {
            warning = true
        }
    }

    @Test
    fun captureFailureDoesNotBlockDismissal() {
        val journal = MemoryJournal().apply { failBegin = true }
        var reconciled = false
        CompletionTransition.apply(
            occurrence,
            occurrence.copy(stage = OccurrenceStage.COMPLETED),
            journal,
        ) {
            reconciled = true
        }
        assertTrue(reconciled)
        assertEquals(OccurrenceStage.COMPLETED, journal.current!!.stage)
        assertTrue(journal.warning)
    }

    @Test
    fun schedulingFailureRestoresAlarmAndRemovesUnpublishedObservation() {
        val journal = MemoryJournal()
        try {
            CompletionTransition.apply(
                occurrence,
                occurrence.copy(stage = OccurrenceStage.COMPLETED),
                journal,
            ) {
                if (it.terminal) error("OS failed")
            }
            fail("Expected reconciliation failure")
        } catch (_: IllegalStateException) {}
        assertEquals(occurrence, journal.current)
        assertFalse(journal.pending)
        assertFalse(journal.settled)
    }

    @Test
    fun interruptionAtSettlementLeavesRecoverableObservationAndDoesNotRejectDismissal() {
        val journal = MemoryJournal().apply { failSettle = true }
        CompletionTransition.apply(
            occurrence,
            occurrence.copy(stage = OccurrenceStage.COMPLETED),
            journal,
        ) {}
        assertTrue(journal.pending)
        assertTrue(journal.warning)
        assertEquals(OccurrenceStage.COMPLETED, journal.current!!.stage)
    }

    @Test
    fun intermediateVerificationAndTimersDoNotProduceCompletions() {
        for (next in
            listOf(
                occurrence.copy(stage = OccurrenceStage.SECOND_WAIT),
                occurrence.copy(stage = OccurrenceStage.VERIFY_DELAY),
            )) {
            val journal = MemoryJournal()
            CompletionTransition.apply(occurrence, next, journal) {}
            assertFalse(journal.settled)
            assertFalse(journal.pending)
        }
        val timer = occurrence.copy(source = OccurrenceSource.TIMER)
        val journal = MemoryJournal()
        CompletionTransition.apply(timer, timer.copy(stage = OccurrenceStage.COMPLETED), journal) {}
        assertFalse(journal.settled)
    }
}
