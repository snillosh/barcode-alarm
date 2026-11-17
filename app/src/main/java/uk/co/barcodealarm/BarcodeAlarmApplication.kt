package uk.co.barcodealarm

import android.app.Application
import android.util.Log
import androidx.work.Configuration

/** WorkManager is initialized on demand, not on an alarm receiver's process-start path. */
class BarcodeAlarmApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()
}
