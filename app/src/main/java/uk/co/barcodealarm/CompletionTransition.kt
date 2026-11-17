package uk.co.barcodealarm

interface CompletionStorage {
    fun save(value: Occurrence)

    fun beginCompletion(next: Occurrence): String

    fun settleCompletion(eventId: String)

    fun rollbackCompletion(previous: Occurrence, eventId: String)

    fun reportCaptureFailure()
}

/** Capture must never become a new reason to reject an otherwise valid dismissal. */
object CompletionTransition {
    fun apply(
        previous: Occurrence,
        next: Occurrence,
        storage: CompletionStorage,
        reconcile: (Occurrence) -> Unit,
    ) {
        if (previous == next) return
        val completion =
            previous.source == OccurrenceSource.ALARM &&
                !previous.terminal &&
                next.stage == OccurrenceStage.COMPLETED
        var eventId: String? = null
        if (completion) {
            try {
                eventId = storage.beginCompletion(next)
            } catch (_: Exception) {
                storage.reportCaptureFailure()
            }
        }
        if (eventId == null) storage.save(next)
        try {
            reconcile(next)
        } catch (exception: Exception) {
            if (eventId != null) storage.rollbackCompletion(previous, eventId)
            else storage.save(previous)
            try {
                reconcile(previous)
            } catch (_: Exception) {}
            throw exception
        }
        if (eventId != null) {
            // If this fails the persisted recovery marker is sufficient to finish after restart.
            try {
                storage.settleCompletion(eventId)
            } catch (_: Exception) {
                storage.reportCaptureFailure()
            }
        }
    }
}
