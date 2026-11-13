package uk.co.barcodealarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackVolumeTest {
    private val enabled = PlaybackSettings(gradualVolumeEnabled = true, rampDurationSeconds = 60)

    private fun gain(elapsed: Long, maths: Boolean = false) =
        PlaybackVolume.calculateGain(enabled, 1000, 1000 + elapsed, maths)

    @Test
    fun existingDefaultStillStartsAtFullGain() {
        assertEquals(1f, PlaybackVolume.calculateGain(PlaybackSettings(), 0, 0, false), 0.0001f)
    }

    @Test
    fun startsAtTwentyPercent() {
        assertEquals(0.2f, gain(0), 0.0001f)
    }

    @Test
    fun halfwayIsSixtyPercent() {
        assertEquals(0.6f, gain(30000), 0.0001f)
    }

    @Test
    fun reachesFullGainAtDuration() {
        assertEquals(1f, gain(60000), 0.0001f)
    }

    @Test
    fun clampsAfterDuration() {
        assertEquals(1f, gain(600000), 0.0001f)
    }

    @Test
    fun gainIsMonotonicAndBounded() {
        var previous = 0f
        for (elapsed in 0L..120000L step 250) {
            val current = gain(elapsed)
            assertTrue(current >= previous && current in 0.2f..1f)
            previous = current
        }
    }

    @Test
    fun mathsScalesCurrentRampRatherThanReplacingIt() {
        assertEquals(0.12f, gain(30000, true), 0.0001f)
    }

    @Test
    fun mathsAtFullRampIsTwentyPercent() {
        assertEquals(0.2f, gain(60000, true), 0.0001f)
    }

    @Test
    fun leavingMathsRestoresCurrentRampGain() {
        assertEquals(gain(30000), gain(30000, true) / 0.2f, 0.0001f)
    }

    @Test
    fun legacyMathsGainRemainsTwentyPercent() {
        assertEquals(0.2f, PlaybackVolume.calculateGain(PlaybackSettings(), 0, 0, true), 0.0001f)
    }

    @Test
    fun persistedStartResumesAtElapsedPositionInsteadOfStartingQuietlyAgain() {
        assertEquals(0.8f, PlaybackVolume.calculateGain(enabled, 1000, 46000, false), 0.0001f)
    }

    @Test
    fun allOfferedDurationsReachFullGain() {
        for (seconds in PlaybackSettings.SUPPORTED_DURATIONS_SECONDS) assertEquals(
            1f,
            PlaybackVolume.calculateGain(
                enabled.copy(rampDurationSeconds = seconds),
                0,
                seconds * 1000L,
                false,
            ),
            0.0001f,
        )
    }

    @Test
    fun invalidDurationFallsBackToOneMinute() {
        assertEquals(
            0.6f,
            PlaybackVolume.calculateGain(enabled.copy(rampDurationSeconds = 0), 0, 30000, false),
            0.0001f,
        )
    }

    @Test
    fun missingStartAndClockRollbackStayAtStartingGain() {
        assertEquals(0.2f, PlaybackVolume.calculateGain(enabled, -1, 1000, false), 0.0001f)
        assertEquals(0.2f, PlaybackVolume.calculateGain(enabled, 5000, 1000, false), 0.0001f)
    }

    @Test
    fun snoozeAndNewOccurrencesResetRampWithoutResettingMathsOnSnooze() {
        val ringing =
            Alarm(
                1,
                7,
                0,
                "code",
                "QR_CODE",
                isRinging = true,
                playbackStartedAtElapsedMillis = 1000,
                mathsStartedAtElapsedMillis = 500,
                correctAnswerCount = 8,
            )
        val snoozed = AlarmRules.snooze(ringing, 10000)
        assertEquals(-1L, snoozed.playbackStartedAtElapsedMillis)
        assertEquals(500L, snoozed.mathsStartedAtElapsedMillis)
        assertEquals(8, snoozed.correctAnswerCount)
        assertEquals(-1L, AlarmRules.markTriggered(snoozed).playbackStartedAtElapsedMillis)
        assertEquals(-1L, AlarmRules.markDismissed(ringing).playbackStartedAtElapsedMillis)
    }

    @Test
    fun playbackStartRoundTripsThroughSavedJson() {
        val alarm = Alarm(1, 7, 0, "code", "QR_CODE", playbackStartedAtElapsedMillis = 12345)
        assertEquals(alarm, AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(alarm))).single())
    }

    @Test
    fun rebootResetsPlaybackClockButOrdinarySystemChangesKeepIt() {
        val alarm =
            Alarm(
                1,
                7,
                0,
                "code",
                "QR_CODE",
                isRinging = true,
                playbackStartedAtElapsedMillis = 12345,
            )
        val now = java.time.ZonedDateTime.parse("2026-11-13T07:00:00Z[Europe/London]")
        assertEquals(
            -1L,
            AlarmRules.restoreAfterSystemChange(alarm, now, false, true)
                .playbackStartedAtElapsedMillis,
        )
        assertEquals(
            12345L,
            AlarmRules.restoreAfterSystemChange(alarm, now, true, false)
                .playbackStartedAtElapsedMillis,
        )
    }

    @Test
    fun olderSavedAlarmsHaveNoPlaybackClockUntilServiceStarts() {
        val legacy = """[{"id":1,"hour":7,"minute":0,"code":"code","format":"QR_CODE"}]"""
        assertEquals(-1L, AlarmJsonCodec.decode(legacy).single().playbackStartedAtElapsedMillis)
    }

    @Test
    fun duplicateBootBroadcastCannotResetARunningRampOrMathsTimer() {
        org.junit.Assert.assertFalse(AlarmRules.isNewDeviceBoot(3, 3))
    }

    @Test
    fun realRebootStillResetsMonotonicClocks() {
        assertTrue(AlarmRules.isNewDeviceBoot(3, 4))
    }

    @Test
    fun olderInstallWithoutBootIdentityRestoresConservatively() {
        assertTrue(AlarmRules.isNewDeviceBoot(-1, 4))
    }

    @Test
    fun unavailableBootIdentityKeepsExistingBootSafetyBehaviour() {
        assertTrue(AlarmRules.isNewDeviceBoot(3, -1))
    }
}
