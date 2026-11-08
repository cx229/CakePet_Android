package com.cx.cakepet

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.ViewGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Shared form-only field. The label is ALWAYS on the outline notch,
 * even when the value is empty and the field is unfocused.
 *
 * Do not use for slider-bound numeric controls in settings/threshold pages.
 */
class FormInputField(context: Context) : TextInputLayout(context) {
    val input = TextInputEditText(context)

    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        isExpandedHintEnabled = false
        setBoxCornerRadii(dp(10f), dp(10f), dp(10f), dp(10f))
        setBoxStrokeColorStateList(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(context.getColor(R.color.cp_sub), context.getColor(R.color.cp_btn_outline_stroke))
        ))
        setDefaultHintTextColor(ColorStateList.valueOf(context.getColor(R.color.cp_sub)))
        setHintTextColor(ColorStateList.valueOf(context.getColor(R.color.cp_sub)))
        setPlaceholderTextColor(ColorStateList.valueOf(context.getColor(R.color.cp_placeholder)))
        input.setTextColor(context.getColor(R.color.cp_text))
        input.setHintTextColor(context.getColor(R.color.cp_note))
        input.textSize = 15f
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine(true)
        input.setPadding(dp(12f).toInt(), dp(11f).toInt(), dp(12f).toInt(), dp(9f).toInt())
        addView(input, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun configure(
        title: String,
        value: String = "",
        placeholder: String = "请输入$title",
        helper: String? = null,
        multiline: Boolean = false
    ): TextInputEditText {
        hint = title
        setPlaceholderText(placeholder)
        helperText = helper?.takeIf { it.isNotBlank() }
        if (multiline) {
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            input.isSingleLine = false
            input.minLines = 3
            input.maxLines = 8
        }
        input.setText(value)
        return input
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
