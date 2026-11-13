package uk.co.barcodealarm

/** A scanned code cannot be edited in place; only its display name can change. */
data class SavedBarcode(
    val id: Int,
    val name: String,
    val barcodeValue: String,
    val barcodeFormat: String,
)

data class BarcodeMigration(val barcodes: List<SavedBarcode>, val alarms: List<Alarm>)

/** Pure library operations return new lists rather than modifying the caller's list. */
object BarcodeLibrary {
    private fun validateName(
        name: String,
        barcodes: List<SavedBarcode>,
        excludedId: Int = 0,
    ): String {
        val trimmedName = name.trim()
        // require(...) throws IllegalArgumentException, similar to C# argument validation.
        require(trimmedName.isNotEmpty()) { "Give this barcode a name" }
        require(trimmedName.length <= 120) { "Use a name of at most 120 characters" }

        val nameAlreadyUsed =
            barcodes.any { barcode ->
                barcode.id != excludedId && barcode.name.equals(trimmedName, ignoreCase = true)
            }
        require(!nameAlreadyUsed) { "That name is already in use" }
        return trimmedName
    }

    fun add(
        barcodes: List<SavedBarcode>,
        name: String,
        value: String,
        format: String,
    ): List<SavedBarcode> {
        require(value.isNotEmpty() && format.isNotEmpty()) { "Scan a barcode first" }
        val duplicate =
            barcodes.find { barcode ->
                AlarmRules.barcodesMatch(barcode.barcodeValue, barcode.barcodeFormat, value, format)
            }
        require(duplicate == null) { "Already saved as ${duplicate?.name}" }

        val nextId = (barcodes.maxOfOrNull { barcode -> barcode.id } ?: 0) + 1
        val barcode = SavedBarcode(nextId, validateName(name, barcodes), value, format)
        return barcodes + barcode
    }

    fun rename(barcodes: List<SavedBarcode>, id: Int, name: String): List<SavedBarcode> {
        require(barcodes.any { barcode -> barcode.id == id }) { "Barcode no longer exists" }
        val validatedName = validateName(name, barcodes, excludedId = id)
        return barcodes.map { barcode ->
            if (barcode.id == id) barcode.copy(name = validatedName) else barcode
        }
    }

    fun delete(barcodes: List<SavedBarcode>, id: Int, alarms: List<Alarm>): List<SavedBarcode> {
        val isUsedByAlarm =
            alarms.any { alarm -> alarm.barcodeId == id || alarm.secondBarcodeId == id }
        require(!isUsedByAlarm) {
            "This barcode is used by an alarm. Choose another barcode for that alarm or delete the alarm first."
        }
        return barcodes.filterNot { barcode -> barcode.id == id }
    }

    fun select(alarm: Alarm, id: Int, barcodes: List<SavedBarcode>): Alarm {
        check(!alarm.isRinging) { "Cannot edit a ringing alarm" }
        if (id == 0)
            return alarm.copy(
                barcodeId = 0,
                barcodeValue = "",
                barcodeFormat = "",
                secondBarcodeId = 0,
                secondBarcodeValue = "",
                secondBarcodeFormat = "",
                scanIntervalMinutes = 0,
            )
        val selectedBarcode =
            barcodes.find { barcode -> barcode.id == id }
                ?: throw IllegalArgumentException("Select a barcode from your library")

        return alarm.copy(
            barcodeId = selectedBarcode.id,
            barcodeValue = selectedBarcode.barcodeValue,
            barcodeFormat = selectedBarcode.barcodeFormat,
        )
    }

    fun selectSecond(alarm: Alarm, id: Int, barcodes: List<SavedBarcode>): Alarm {
        if (id == 0 || alarm.barcodeId == 0)
            return alarm.copy(
                secondBarcodeId = 0,
                secondBarcodeValue = "",
                secondBarcodeFormat = "",
            )
        val second = barcodes.find { it.id == id } ?: error("Select a second barcode")
        require(
            !AlarmRules.barcodesMatch(
                alarm.barcodeValue,
                alarm.barcodeFormat,
                second.barcodeValue,
                second.barcodeFormat,
            )
        ) {
            "Choose different barcodes for each stage"
        }
        return alarm.copy(
            secondBarcodeId = id,
            secondBarcodeValue = second.barcodeValue,
            secondBarcodeFormat = second.barcodeFormat,
        )
    }

    fun migrate(barcodes: List<SavedBarcode>, alarms: List<Alarm>): BarcodeMigration {
        var library = barcodes
        val migratedAlarms = mutableListOf<Alarm>()

        for (alarm in alarms) {
            if (alarm.barcodeId != 0 || alarm.barcodeValue.isEmpty()) {
                migratedAlarms.add(alarm)
                continue
            }

            var matchingBarcode =
                library.find { barcode ->
                    AlarmRules.barcodesMatch(
                        barcode.barcodeValue,
                        barcode.barcodeFormat,
                        alarm.barcodeValue,
                        alarm.barcodeFormat,
                    )
                }
            if (matchingBarcode == null) {
                var nameNumber = 1
                while (
                    library.any { barcode ->
                        barcode.name.equals("Saved barcode $nameNumber", ignoreCase = true)
                    }
                ) {
                    nameNumber++
                }
                library =
                    add(
                        library,
                        "Saved barcode $nameNumber",
                        alarm.barcodeValue,
                        alarm.barcodeFormat,
                    )
                matchingBarcode = library.last()
            }

            // Only add the link. Scheduled times, ringing state, maths progress and code stay
            // intact.
            migratedAlarms.add(alarm.copy(barcodeId = matchingBarcode.id))
        }
        return BarcodeMigration(library, migratedAlarms)
    }
}
