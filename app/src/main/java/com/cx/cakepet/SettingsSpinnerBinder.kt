package com.cx.cakepet

import android.view.Gravity
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
        spinner.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                TextView(ctx).apply {
                    text = entries[position]
                    textSize = 13f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    setTextColor(ctx.getColor(R.color.cp_title))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    if (position > 0) addView(View(ctx).apply {
                        setBackgroundColor(ctx.getColor(R.color.cp_subtle))
                    }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                        leftMargin = dp(8)
                        rightMargin = dp(8)
                    })
                    addView(TextView(ctx).apply {
                        text = entries[position]
                        textSize = 11f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(ctx.getColor(R.color.cp_title))
                        setPadding(dp(16), 0, dp(16), 0)
                    }, LinearLayout.LayoutParams(-1, dp(48)))
                }
        }
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
