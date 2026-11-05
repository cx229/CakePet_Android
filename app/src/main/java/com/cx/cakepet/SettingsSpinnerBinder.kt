package com.cx.cakepet

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView

/** Exactly the presentation established by the keyboard-adaptation dropdown. */
object SettingsSpinnerBinder {
    fun bind(spinner: Spinner, entries: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        spinner.adapter = object : ArrayAdapter<String>(
            spinner.context, android.R.layout.simple_spinner_item, entries
        ) {
            private fun align(view: View): View = view.apply {
                (this as? TextView)?.apply {
                    textSize = 13f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                }
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                align(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                align(super.getDropDownView(position, convertView, parent))
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.minimumHeight = (44 * spinner.resources.displayMetrics.density).toInt()
        spinner.setSelection(selected.coerceIn(0, entries.lastIndex))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
