package com.cx.cakepet

import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Shared dropdown appearance with subtle item separators, while preserving selection callbacks. */
object SettingsSpinnerBinder {
    fun bind(spinner: Spinner, entries: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val ctx = spinner.context
        val density = spinner.resources.displayMetrics.density
        fun dp(n: Int) = (n * density + .5f).toInt()
        fun graySurface(): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(ctx.getColor(R.color.cp_bg))
        }
        // Background belongs to the entire control (result AND arrow), not the
        // selected-label TextView; drawable arrow shares the same shaded surface.
        spinner.background = graySurface()
        spinner.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                TextView(ctx).apply {
                    text = entries[position]
                    textSize = 13f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    setTextColor(ctx.getColor(R.color.cp_title))
                    val arrow = androidx.core.content.ContextCompat.getDrawable(ctx,
                        android.R.drawable.arrow_down_float)?.mutate()
                    arrow?.setTint(ctx.getColor(R.color.cp_title))
                    setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
                    compoundDrawablePadding = dp(8)
                    setPadding(dp(12), 0, dp(12), 0)
                    minHeight = dp(44)
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(ctx.getColor(R.color.cp_card_bg))
                    if (position > 0) addView(View(ctx).apply {
                        setBackgroundColor(ctx.getColor(R.color.cp_subtle))
                    }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                        leftMargin = dp(8)
                        rightMargin = dp(8)
                    })
                    addView(TextView(ctx).apply {
                        text = entries[position]
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(ctx.getColor(R.color.cp_title))
                        setPadding(dp(16), 0, dp(16), 0)
                    }, LinearLayout.LayoutParams(-1, dp(48)))
                }
        }
        spinner.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(ctx.getColor(R.color.cp_card_bg)))
        spinner.minimumHeight = dp(44)
        if (entries.isNotEmpty()) spinner.setSelection(selected.coerceIn(0, entries.lastIndex))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
