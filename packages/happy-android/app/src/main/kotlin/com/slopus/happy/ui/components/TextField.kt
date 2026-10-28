package com.slopus.happy.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.theme.Fonts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import kotlin.math.roundToInt

/**
 * A text input in one of the app's text styles (UITextField / UITextView
 * where happy-ios has them): the platform's EditText, with no underline or
 * padding of its own, so the screen's layout decides its frame.
 */
class TextField(
    context: Context,
    style: TextStyle,
    private val color: ColorToken = Palette.inputText,
    private val placeholderColor: ColorToken = Palette.inputPlaceholder,
    multiline: Boolean = false,
) : EditText(context), ThemeAware {
    var style: TextStyle = style
        set(value) { field = value; applyStyle() }

    var onChange: ((String) -> Unit)? = null
    var onReturn: (() -> Unit)? = null

    val string: String get() = text?.toString().orEmpty()

    init {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
        if (!multiline) {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange?.invoke(s?.toString().orEmpty())
            }
        })
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO) {
                onReturn?.invoke() ?: dismissKeyboard()
                true
            } else {
                false
            }
        }
        applyStyle()
        applyTheme()
    }

    /** A URL field: no capitals, no corrections, the URL keyboard. */
    fun urlKeyboard() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun applyStyle() {
        val scaled = style.scaled(Theme.textScale)
        typeface = Fonts.typeface(style.face)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (scaled.size * Theme.density).toFloat())
        if (style.letterSpacing != 0.0) letterSpacing = (style.letterSpacing / style.size).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) scaled.lineHeight?.let { lineHeight = (it * Theme.density).roundToInt() }
    }

    fun focus() {
        requestFocus()
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun dismissKeyboard() {
        clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun applyTheme() {
        setTextColor(Theme.color(color))
        setHintTextColor(Theme.color(placeholderColor))
        highlightColor = Theme.color(Palette.iconBlue) and 0x40FFFFFF
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTintList(ColorStateList.valueOf(Theme.color(Palette.text)))
    }
}
