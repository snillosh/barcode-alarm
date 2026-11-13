package uk.co.barcodealarm

enum class Page {
    HOME,
    SETTINGS,
    EDITOR,
    BARCODES,
}

enum class BackDestination {
    SYSTEM,
    HOME,
    EDITOR,
    CONFIRM_DISCARD,
    RINGING,
}

/** Navigation decisions are separate from Android views so they can be unit tested. */
object Navigation {
    fun restorePage(name: String?, hasDraft: Boolean): Page {
        val restoredPage = Page.entries.find { page -> page.name == name } ?: Page.HOME
        if (restoredPage == Page.EDITOR && !hasDraft) {
            return Page.HOME
        }
        return restoredPage
    }

    fun back(page: Page, hasDraft: Boolean, hasChanges: Boolean = false): BackDestination {
        return when (page) {
            Page.HOME -> BackDestination.SYSTEM
            Page.SETTINGS -> BackDestination.HOME
            // A draft identifies the caller when the library was opened from the editor.
            Page.BARCODES -> if (hasDraft) BackDestination.EDITOR else BackDestination.HOME
            Page.EDITOR -> if (hasChanges) BackDestination.CONFIRM_DISCARD else BackDestination.HOME
        }
    }

    fun hasUnsavedChanges(draft: Alarm, savedAlarm: Alarm?): Boolean {
        val original = savedAlarm ?: Alarm(draft.id, 7, 0, "", "")
        // Execution state is not an edit. Compare only controls available in the editor.
        return draft.hour != original.hour ||
            draft.minute != original.minute ||
            draft.barcodeId != original.barcodeId ||
            draft.repeatDays != original.repeatDays ||
            draft.soundUri != original.soundUri
    }

    fun ringingBack(isMathsVisible: Boolean): BackDestination {
        return if (isMathsVisible) BackDestination.RINGING else BackDestination.SYSTEM
    }
}
