package uk.co.barcodealarm

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WellnessPersistenceTest {
    private lateinit var context: Context
    private val active =
        Occurrence(1000000, 1, Alarm(1000000, 7, 0, "", "", occurrenceScheduledAtMillis = 1000))

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("occurrences", 0).edit().clear().commit()
        context.getSharedPreferences("wellness_sync", 0).edit().clear().commit()
    }

    @Test
    fun terminalStateAndJournalSurviveStoreRecreationAndRecoveryIsIdempotent() {
        val store = OccurrenceStore(context)
        store.save(active)
        val id = store.beginCompletion(active.copy(stage = OccurrenceStage.COMPLETED))
        val recreated = OccurrenceStore(context)
        assertNull(recreated.get(active.id))
        assertTrue(recreated.pending(WellnessConnection.DEFAULT_SERVER).isEmpty())
        assertEquals(id, recreated.history().single().event.eventId)
        OccurrenceManager(context).recoverCompletions()
        OccurrenceManager(context).recoverCompletions()
        assertEquals(
            id,
            OccurrenceStore(context)
                .pending(WellnessConnection.DEFAULT_SERVER)
                .single()
                .event
                .eventId,
        )
        assertNull(OccurrenceStore(context).history().single().recovery)
    }

    @Test
    fun rollbackRetainsEarlierHistoryAndTheOriginalActiveState() {
        val store = OccurrenceStore(context)
        val first = store.beginCompletion(active.copy(stage = OccurrenceStage.COMPLETED))
        store.settleCompletion(first)
        val next = active.copy(id = active.id + 1)
        store.save(next)
        val failed = store.beginCompletion(next.copy(stage = OccurrenceStage.COMPLETED))
        store.rollbackCompletion(next, failed)
        assertEquals(next, OccurrenceStore(context).get(next.id))
        assertEquals(first, OccurrenceStore(context).history().single().event.eventId)
    }

    @Test
    fun definitionDeletionIsNotAnAlarmCompletion() {
        OccurrenceStore(context).save(active)
        OccurrenceManager(context).cancelDefinition(active.definitionId)
        assertTrue(OccurrenceStore(context).history().isEmpty())
        assertNull(OccurrenceStore(context).get(active.id))
    }

    @Test
    fun sourceIdentityPersistsAcrossRecordsAndAcknowledgementDoesNotRewriteActiveAlarms() {
        val store = OccurrenceStore(context)
        val first = store.beginCompletion(active.copy(stage = OccurrenceStage.COMPLETED))
        store.settleCompletion(first)
        val second =
            store.beginCompletion(
                active.copy(id = active.id + 1, stage = OccurrenceStage.COMPLETED)
            )
        store.settleCompletion(second)
        store.save(active)
        store.acknowledge(first, WellnessConnection.DEFAULT_SERVER, 1234)
        val records = OccurrenceStore(context).history()
        assertEquals(records[0].event.installationId, records[1].event.installationId)
        assertEquals(active, store.get(active.id))
        assertEquals(1, store.pending(WellnessConnection.DEFAULT_SERVER).size)
        assertEquals(2, store.pending("https://new.example.com").size)
    }
}
