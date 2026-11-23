package uk.co.barcodealarm

import java.time.DayOfWeek

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
    val firstBarcodeName: String = "",
    val secondBarcodeName: String = "",
    val secondBarcodeId: Int = 0,
    val secondBarcodeValue: String = "",
    val secondBarcodeFormat: String = "",
    val scanIntervalMinutes: Int = 1,
    val verificationEnabled: Boolean = false,
    val verificationDelayMinutes: Int = 5,
    val verificationResponseMinutes: Int = 2,
    // Empty means one-shot. Java uses ISO weekday numbers: Monday=1 through Sunday=7.
    val repeatDays: Set<DayOfWeek> = emptySet(),
    // True only while a snooze delivery is pending, rather than a regular scheduled day.
    val isSnoozed: Boolean = false,
    // Retain the original occurrence through snoozes, including changes to the wall clock.
    val occurrenceScheduledAtMillis: Long = 0,
    // Separate from the maths timer. Persisted so process recreation cannot restart the ramp.
    val playbackStartedAtElapsedMillis: Long = -1,
    val label: String = "",
    val canBeSnoozed: Boolean = true,
    val alarmProtectionMinutes: Int = 5,
)
