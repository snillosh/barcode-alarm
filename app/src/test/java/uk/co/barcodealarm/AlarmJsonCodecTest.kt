package uk.co.barcodealarm

import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmJsonCodecTest {
    private val legacyJson =
        """[{"id":1,"hour":7,"minute":30,"code":"123","format":"QR_CODE","enabled":true,"due":123456,"barcodeId":2}]"""

    @Test
    fun oldSavedAlarmsRemainOneShotWithSamePendingTime() {
        val decoded = AlarmJsonCodec.decode(legacyJson).single()
        assertEquals(emptySet<DayOfWeek>(), decoded.repeatDays)
        assertFalse(decoded.isSnoozed)
        assertEquals(123456L, decoded.scheduledAtMillis)
        assertTrue(decoded.isEnabled)
        assertEquals(2, decoded.barcodeId)
    }

    @Test
    fun repeatSnoozeAndChallengeStateRoundTrip() {
        val alarm =
            Alarm(
                1,
                7,
                30,
                "123",
                "QR_CODE",
                soundUri = "content://alarm",
                isEnabled = true,
                scheduledAtMillis = 123456,
                isRinging = false,
                mathsStartedAtElapsedMillis = 100,
                correctAnswerCount = 5,
                barcodeId = 2,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                isSnoozed = true,
                occurrenceScheduledAtMillis = 123000,
            )
        assertEquals(listOf(alarm), AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(alarm))))
    }

    @Test
    fun existingJsonKeysRemainCompatible() {
        val encoded =
            JSONObject(
                JSONArray(AlarmJsonCodec.encode(AlarmJsonCodec.decode(legacyJson)))
                    .getJSONObject(0)
                    .toString()
            )
        assertEquals("123", encoded.getString("code"))
        assertEquals("QR_CODE", encoded.getString("format"))
        assertEquals(123456L, encoded.getLong("due"))
        assertEquals(0, encoded.getJSONArray("repeatDays").length())
    }

    @Test
    fun weekdaysUseIsoNumbersAndIgnoreInvalidOrDuplicateEntries() {
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
            AlarmJsonCodec.decodeRepeatDays(JSONArray("[1,7,1,0,8]")),
        )
    }

    @Test
    fun emptyListsRoundTrip() {
        assertEquals(emptyList<Alarm>(), AlarmJsonCodec.decode(AlarmJsonCodec.encode(emptyList())))
    }
}
