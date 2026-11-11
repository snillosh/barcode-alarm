package uk.co.barcodealarm

/**
 * An immutable saved alarm, similar to a C# record with init-only properties.
 *
 * `copy(...)` creates a changed record (like `with` in C#); it never changes this instance.
 * AlarmStore keeps the existing JSON field names so upgrades retain previously saved alarms.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    // A snapshot keeps dismissal independent of library renaming or UI lifecycle changes.
    val barcodeValue: String,
    val barcodeFormat: String,
    val soundUri: String = "",
    val isEnabled: Boolean = false,
    // Wall-clock Unix milliseconds, used only for Android's scheduled occurrence.
    val scheduledAtMillis: Long = 0,
    val isRinging: Boolean = false,
    // Monotonic milliseconds since boot. -1 means the maths challenge has not started.
    val mathsStartedAtElapsedMillis: Long = -1,
    val correctAnswerCount: Int = 0,
    // Zero means no library selection; persisted alarms normally have a positive ID.
    val barcodeId: Int = 0,
)
