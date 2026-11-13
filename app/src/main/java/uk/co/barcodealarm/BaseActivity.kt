package uk.co.barcodealarm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.zxing.integration.android.IntentIntegrator

/**
 * Shared native UI and Android Back plumbing. Think of this as a base Window/Form class. Alarm
 * scheduling and playback deliberately do not live here.
 */
open class BaseActivity : Activity() {
    protected val appearancePreferences
        get() = getSharedPreferences("appearance", MODE_PRIVATE)

    protected val isDarkModeEnabled: Boolean
        get() = appearancePreferences.getBoolean("dark_mode", false)

    protected val textColor: Int
        get() = getThemeColor(R.color.light_ink, R.color.dark_ink)

    protected val secondaryTextColor: Int
        get() = getThemeColor(R.color.light_muted, R.color.dark_muted)

    protected val accentColor: Int
        get() = getThemeColor(R.color.light_accent, R.color.dark_accent)

    private val backgroundColor: Int
        get() = getThemeColor(R.color.light_background, R.color.dark_background)

    private val surfaceColor: Int
        get() = getThemeColor(R.color.light_surface, R.color.dark_surface)

    private val borderColor: Int
        get() = getThemeColor(R.color.light_border, R.color.dark_border)

    private val secondaryButtonColor: Int
        get() = getThemeColor(R.color.light_secondary, R.color.dark_secondary)

    private val primaryButtonTextColor: Int
        get() = getThemeColor(R.color.light_on_accent, R.color.dark_on_accent)

    // lateinit means Android initializes this after construction, before it is first used.
    protected lateinit var contentLayout: LinearLayout
    private var backAction: (() -> Unit)? = null
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the theme before Android creates native controls (time picker, dialogs, etc.).
        setTheme(if (isDarkModeEnabled) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    private fun getThemeColor(lightResource: Int, darkResource: Int): Int {
        return getColor(if (isDarkModeEnabled) darkResource else lightResource)
    }

    /** A nullable Kotlin function type is similar to `Action?` in C#. Null restores system Back. */
    protected fun setBackAction(action: (() -> Unit)?) {
        backAction = action
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val previousCallback = backCallback
        if (previousCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(previousCallback)
        }
        backCallback = null
        if (action != null) {
            val callback = OnBackInvokedCallback { backAction?.invoke() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        }
    }

    // This override serves Android 9–12 only. Newer versions use the dispatcher above.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Compatibility for Android 9–12; newer Android uses OnBackInvokedCallback")
    override fun onBackPressed() {
        val action = backAction
        if (action != null) {
            action()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        setBackAction(null)
        super.onDestroy()
    }

    protected fun toPixels(densityIndependentPixels: Int): Int {
        // Android layout sizes use dp so controls appear the same size on different screens.
        return (densityIndependentPixels * resources.displayMetrics.density).toInt()
    }

    private fun createRoundedBackground(
        color: Int,
        radius: Int,
        border: Int? = null,
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = toPixels(radius).toFloat()
        if (border != null) {
            drawable.setStroke(toPixels(1), border)
        }
        return drawable
    }

    protected fun showPage(title: String, subtitle: String? = null) {
        setBackAction(null)
        updateSystemBarColors()
        contentLayout = LinearLayout(this)
        contentLayout.orientation = LinearLayout.VERTICAL
        contentLayout.setPadding(toPixels(24), toPixels(24), toPixels(24), toPixels(28))

        val scrollView = ScrollView(this)
        scrollView.setBackgroundColor(backgroundColor)
        scrollView.isFillViewport = true
        scrollView.clipToPadding = false
        scrollView.addView(contentLayout)
        applySystemBarInsets(scrollView)
        setContentView(scrollView)
        scrollView.requestApplyInsets()

        val brand = addText("B A R C O D E   A L A R M", 11f)
        brand.setTextColor(accentColor)
        brand.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        val heading = addText(title, 34f)
        heading.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        heading.setPadding(0, toPixels(10), 0, toPixels(4))
        if (subtitle != null) {
            addCaption(subtitle)
        }
        addSpace(18)
    }

    private fun updateSystemBarColors() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility =
            if (isDarkModeEnabled) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
    }

    private fun applySystemBarInsets(scrollView: ScrollView) {
        // Insets are the areas occupied by the status bar, camera cutout and navigation bar.
        scrollView.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val areas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val systemBars = insets.getInsets(areas)
                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom,
                )
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    protected fun addText(text: String, size: Float = 18f): TextView {
        val textView = TextView(this)
        textView.text = text
        textView.textSize = size
        textView.setTextColor(textColor)
        textView.setLineSpacing(toPixels(3).toFloat(), 1f)
        val layout = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        layout.bottomMargin = toPixels(8)
        contentLayout.addView(textView, layout)
        return textView
    }

    protected fun addCaption(text: String): TextView {
        val caption = addText(text, 14f)
        caption.setTextColor(secondaryTextColor)
        return caption
    }

    protected fun addSpace(height: Int) {
        contentLayout.addView(View(this), LinearLayout.LayoutParams(1, toPixels(height)))
    }

    /** The callback adds children to this card. It is an Action-style callback, not a framework. */
    protected fun addCard(addChildren: () -> Unit) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(toPixels(20), toPixels(20), toPixels(20), toPixels(14))
        card.background = createRoundedBackground(surfaceColor, 24, borderColor)
        val layout = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        layout.bottomMargin = toPixels(16)
        contentLayout.addView(card, layout)
        addToContainer(card, addChildren)
    }

    protected fun addButtonRow(addButtons: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        contentLayout.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addToContainer(row, addButtons)
    }

    private fun addToContainer(container: LinearLayout, addChildren: () -> Unit) {
        val parent = contentLayout
        contentLayout = container
        try {
            addChildren()
        } finally {
            // Restore the parent even if building a child control throws an exception.
            contentLayout = parent
        }
    }

    protected fun addButton(
        text: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        val button = Button(this)
        button.stateListAnimator = null
        button.elevation = 0f
        button.text = text
        button.isAllCaps = false
        button.textSize = 16f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val buttonTextColor =
            when {
                primary -> primaryButtonTextColor
                danger && isDarkModeEnabled -> Color.rgb(255, 177, 170)
                danger -> Color.rgb(151, 54, 49)
                else -> accentColor
            }
        button.setTextColor(buttonTextColor)
        val rippleColor =
            if (isDarkModeEnabled) Color.argb(45, 200, 240, 210) else Color.argb(35, 80, 110, 90)
        val fillColor = if (primary) accentColor else secondaryButtonColor
        button.background =
            RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                createRoundedBackground(fillColor, 16),
                null,
            )
        button.minHeight = toPixels(54)
        button.minimumHeight = toPixels(54)
        button.gravity = Gravity.CENTER
        button.setPadding(toPixels(16), toPixels(12), toPixels(16), toPixels(12))
        button.layoutParams = createButtonLayout()
        button.setOnClickListener { onClick() }
        contentLayout.addView(button)
    }

    private fun createButtonLayout(): LinearLayout.LayoutParams {
        val isHorizontalRow = contentLayout.orientation == LinearLayout.HORIZONTAL
        // Buttons in a row share the available width using equal layout weights.
        val layout =
            if (isHorizontalRow) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        layout.topMargin = toPixels(5)
        layout.bottomMargin = toPixels(7)
        if (isHorizontalRow) {
            layout.marginStart = toPixels(3)
            layout.marginEnd = toPixels(3)
        }
        return layout
    }

    private var currentMessage: Toast? = null

    protected fun showMessage(text: String, short: Boolean = false) {
        currentMessage?.cancel()
        currentMessage =
            Toast.makeText(this, text, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
        currentMessage?.show()
    }

    protected fun runUiAction(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            AlarmStore(this).log(exception.toString())
            showMessage(exception.message ?: "Action failed")
        }
    }

    protected fun openBarcodeScanner() {
        val scanner = IntentIntegrator(this)
        scanner.setCaptureActivity(PortraitCaptureActivity::class.java)
        scanner.setDesiredBarcodeFormats(listOf("EAN_13", "UPC_A", "UPC_E", "CODE_128", "QR_CODE"))
        scanner.setPrompt("Place your barcode inside the frame")
        scanner.setBeepEnabled(false)
        scanner.setOrientationLocked(true)
        scanner.initiateScan()
    }
}
