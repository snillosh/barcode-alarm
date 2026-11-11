package uk.co.barcodealarm

import com.journeyapps.barcodescanner.CaptureActivity

/** ZXing supplies scanning; the manifest fixes this Activity's camera orientation to portrait. */
class PortraitCaptureActivity : CaptureActivity()
