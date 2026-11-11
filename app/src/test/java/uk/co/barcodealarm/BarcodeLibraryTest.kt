package uk.co.barcodealarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library functions are pure: tests need no Android emulator or mocks. */
class BarcodeLibraryTest {
    private val shampoo = SavedBarcode(1, "Home bathroom shampoo", "123", "QR_CODE")
    private val alarm =
        Alarm(
            id = 5,
            hour = 7,
            minute = 30,
            barcodeValue = "old",
            barcodeFormat = "QR_CODE",
            soundUri = "sound",
            isEnabled = true,
            scheduledAtMillis = 123456,
        )

    private fun assertInvalidArgument(action: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, action)
    }

    @Test
    fun addingTrimsNameAndKeepsScannedCode() {
        val savedBarcode =
            BarcodeLibrary.add(emptyList(), "  Shampoo  ", " value ", "QR_CODE").single()
        assertEquals("Shampoo", savedBarcode.name)
        assertEquals(" value ", savedBarcode.barcodeValue)
        assertEquals(1, savedBarcode.id)
    }

    @Test
    fun namesMustNotBeBlank() {
        assertInvalidArgument { BarcodeLibrary.add(emptyList(), "  ", "123", "QR_CODE") }
    }

    @Test
    fun namesHaveReasonableLimit() {
        assertInvalidArgument { BarcodeLibrary.add(emptyList(), "x".repeat(121), "123", "QR_CODE") }
    }

    @Test
    fun namesCannotBeAmbiguousInDropdown() {
        assertInvalidArgument {
            BarcodeLibrary.add(listOf(shampoo), "HOME BATHROOM SHAMPOO", "other", "QR_CODE")
        }
    }

    @Test
    fun rejectsUnscannedCode() {
        assertInvalidArgument { BarcodeLibrary.add(emptyList(), "Shampoo", "", "QR_CODE") }
    }

    @Test
    fun rejectsDuplicateCode() {
        assertInvalidArgument {
            BarcodeLibrary.add(listOf(shampoo), "Another name", "123", "QR_CODE")
        }
    }

    @Test
    fun rejectsEquivalentUpcDuplicate() {
        val existingBarcode = SavedBarcode(1, "Product", "0123456789012", "EAN_13")
        assertInvalidArgument {
            BarcodeLibrary.add(listOf(existingBarcode), "Same product", "123456789012", "UPC_A")
        }
    }

    @Test
    fun differentFormatsRemainSeparate() {
        assertEquals(2, BarcodeLibrary.add(listOf(shampoo), "Kitchen", "123", "CODE_128").size)
    }

    @Test
    fun selectCopiesCorrectValueAndPreservesSchedule() {
        val selectedAlarm = BarcodeLibrary.select(alarm, 1, listOf(shampoo))
        assertEquals(
            alarm.copy(barcodeValue = "123", barcodeFormat = "QR_CODE", barcodeId = 1),
            selectedAlarm,
        )
    }

    @Test
    fun noSelectionCannotSaveEvenWithOldCode() {
        assertInvalidArgument { BarcodeLibrary.select(alarm, 0, listOf(shampoo)) }
    }

    @Test
    fun deletedSelectionCannotSave() {
        assertInvalidArgument { BarcodeLibrary.select(alarm, 99, listOf(shampoo)) }
    }

    @Test
    fun ringingAlarmCannotChangeBarcode() {
        assertThrows(IllegalStateException::class.java) {
            BarcodeLibrary.select(alarm.copy(isRinging = true), 1, listOf(shampoo))
        }
    }

    @Test
    fun renameChangesOnlyName() {
        val renamed = BarcodeLibrary.rename(listOf(shampoo), 1, "New name").single()
        assertEquals(shampoo.copy(name = "New name"), renamed)
        val selectedAlarm = BarcodeLibrary.select(alarm, 1, listOf(shampoo))
        assertEquals(selectedAlarm, BarcodeLibrary.select(selectedAlarm, 1, listOf(renamed)))
    }

    @Test
    fun renameCanKeepOwnName() {
        assertEquals(listOf(shampoo), BarcodeLibrary.rename(listOf(shampoo), 1, shampoo.name))
    }

    @Test
    fun renameRejectsBlankAndMissingEntry() {
        assertInvalidArgument { BarcodeLibrary.rename(listOf(shampoo), 1, " ") }
        assertInvalidArgument { BarcodeLibrary.rename(listOf(shampoo), 2, "Name") }
    }

    @Test
    fun usedBarcodeCannotBeDeletedEvenWhenAlarmDisabled() {
        assertInvalidArgument {
            BarcodeLibrary.delete(
                listOf(shampoo),
                1,
                listOf(alarm.copy(barcodeId = 1, isEnabled = false)),
            )
        }
    }

    @Test
    fun unusedBarcodeCanBeDeleted() {
        assertTrue(BarcodeLibrary.delete(listOf(shampoo), 1, emptyList()).isEmpty())
    }

    @Test
    fun migrationPreservesAlarmOccurrenceAndChallenge() {
        // Importing the library must not reset a live alarm or its emergency-dismissal progress.
        val legacyAlarm =
            alarm.copy(isRinging = true, mathsStartedAtElapsedMillis = 100, correctAnswerCount = 7)
        val migrated = BarcodeLibrary.migrate(emptyList(), listOf(legacyAlarm))
        assertEquals(
            legacyAlarm.copy(barcodeId = migrated.barcodes.single().id),
            migrated.alarms.single(),
        )
        assertEquals("Saved barcode 1", migrated.barcodes.single().name)
    }

    @Test
    fun migrationDeduplicatesAndPreservesEquivalentPayloads() {
        val eanAlarm = alarm.copy(barcodeValue = "0123456789012", barcodeFormat = "EAN_13")
        val upcAlarm = alarm.copy(id = 6, barcodeValue = "123456789012", barcodeFormat = "UPC_A")
        val migration = BarcodeLibrary.migrate(emptyList(), listOf(eanAlarm, upcAlarm))
        assertEquals(1, migration.barcodes.size)
        assertEquals(eanAlarm.copy(barcodeId = 1), migration.alarms[0])
        assertEquals(upcAlarm.copy(barcodeId = 1), migration.alarms[1])
    }

    @Test
    fun migrationIsIdempotent() {
        val firstMigration = BarcodeLibrary.migrate(emptyList(), listOf(alarm))
        assertEquals(
            firstMigration,
            BarcodeLibrary.migrate(firstMigration.barcodes, firstMigration.alarms),
        )
    }

    @Test
    fun migrationReusesExistingEntryAndAvoidsNameCollisions() {
        val existingBarcode = SavedBarcode(8, "Saved barcode 1", "other", "QR_CODE")
        val migrated =
            BarcodeLibrary.migrate(
                listOf(existingBarcode),
                listOf(alarm, alarm.copy(id = 6, barcodeValue = "other")),
            )
        assertEquals("Saved barcode 2", migrated.barcodes.last().name)
        assertEquals(9, migrated.alarms[0].barcodeId)
        assertEquals(8, migrated.alarms[1].barcodeId)
    }

    @Test
    fun independentAlarmsSelectDifferentEntries() {
        val kitchenBarcode = shampoo.copy(id = 2, name = "Kitchen", barcodeValue = "456")
        val barcodes = listOf(shampoo, kitchenBarcode)
        assertEquals("123", BarcodeLibrary.select(alarm, 1, barcodes).barcodeValue)
        assertEquals("456", BarcodeLibrary.select(alarm.copy(id = 6), 2, barcodes).barcodeValue)
    }
}
