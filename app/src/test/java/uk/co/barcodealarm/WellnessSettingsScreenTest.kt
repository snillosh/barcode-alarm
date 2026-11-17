package uk.co.barcodealarm

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WellnessSettingsScreenTest {
    @Test
    fun credentialEntryIsNotRestorableAutofillableOrScreenCapturable() {
        val controller = Robolectric.buildActivity(WellnessSyncActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            fun inputs(view: View): List<EditText> =
                when (view) {
                    is EditText -> listOf(view)
                    is ViewGroup ->
                        (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
                    else -> emptyList()
                }
            val fields = inputs(activity.window.decorView)
            val credential = fields.single { !it.isSaveEnabled }
            assertEquals(
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                credential.importantForAutofill,
            )
            assertTrue(credential.text.isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
