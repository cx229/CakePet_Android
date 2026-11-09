package com.cx.cakepet

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** App-level display preferences. Uses the same titlebar, module cards and spinner as other settings. */
class PersonalizationSettingsActivity : AppCompatActivity() {
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_threshold_settings)
        findViewById<View>(R.id.status_bar_spacer).apply {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            layoutParams.height = if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
            requestLayout()
        }
        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "个性化设置"
        findViewById<TextView>(R.id.tv_title_sub).text = "自定义软件的显示与交互"
        findViewById<ImageView>(R.id.btn_more).visibility = View.GONE
        val container = findViewById<LinearLayout>(R.id.container)

        fun module(name: String): LinearLayout {
            val heading = layoutInflater.inflate(R.layout.include_module_header, container, false)
            if (container.childCount == 0) {
                (heading.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = 0
            }
            heading.findViewById<TextView>(R.id.tv_module_title).text = name
            container.addView(heading)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = getDrawable(R.drawable.item_divider)
                dividerPadding = dp(16)
                setPadding(0, dp(8), 0, dp(8))
                container.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }

        fun caption(title: String, note: String? = null): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@PersonalizationSettingsActivity).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.cp_title))
                })
                if (note != null) addView(TextView(this@PersonalizationSettingsActivity).apply {
                    text = note
                    textSize = 11f
                    setTextColor(getColor(R.color.cp_note))
                })
            }

        fun selector(card: LinearLayout, title: String, choices: List<String>,
                     selected: Int, indent: Boolean = false, choose: (Int) -> Unit): SettingsRippleRow {
            val row = SettingsRippleRow(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (indent) 32 else 20), dp(8), dp(20), dp(8))
                minimumHeight = dp(60)
            }
            row.addView(caption(title), LinearLayout.LayoutParams(0, -2, 1f))
            val spinner = Spinner(this).apply { minimumHeight = dp(44) }
            row.addView(spinner, LinearLayout.LayoutParams(dp(148), dp(44)))
            SettingsSpinnerBinder.bind(spinner, choices, selected, choose)
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            return row
        }

        val home = module("首页")
        selector(home, "召唤位置", listOf("底部中间", "随机位置"),
            if (PersonalizationPrefs.summonPosition(this) == PersonalizationPrefs.RANDOM) 1 else 0) {
            PersonalizationPrefs.setSummonPosition(this, if (it == 1) PersonalizationPrefs.RANDOM
                else PersonalizationPrefs.BOTTOM_CENTER)
        }

        val previews = module("角色预览")
        val mainRow = SettingsRippleRow(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            minimumHeight = dp(60)
        }
        mainRow.addView(caption("动态预览", "播放角色预览动画"),
            LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = PersonalizationPrefs.animatedPreview(this@PersonalizationSettingsActivity)
            thumbTintList = androidx.core.content.ContextCompat.getColorStateList(
                this@PersonalizationSettingsActivity, R.color.switch_black_selector)
            trackTintList = androidx.core.content.ContextCompat.getColorStateList(
                this@PersonalizationSettingsActivity, R.color.switch_black_selector)
        }
        mainRow.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        previews.addView(mainRow, LinearLayout.LayoutParams(-1, -2))

        val patRow = selector(previews, "预览摸摸头", listOf("开启", "关闭"),
            if (PersonalizationPrefs.previewPat(this)) 0 else 1, true) {
            PersonalizationPrefs.setPreviewPat(this, it == 0)
        }
        val modeRow = selector(previews, "首页预览模式", listOf("常驻", "动态"),
            if (PersonalizationPrefs.homePreviewMode(this) == PersonalizationPrefs.DYNAMIC) 1 else 0, true) {
            PersonalizationPrefs.setHomePreviewMode(this,
                if (it == 1) PersonalizationPrefs.DYNAMIC else PersonalizationPrefs.ALWAYS)
        }
        fun refreshChildren() {
            val visible = if (toggle.isChecked) View.VISIBLE else View.GONE
            patRow.visibility = visible
            modeRow.visibility = visible
        }
        toggle.setOnCheckedChangeListener { _, value ->
            PersonalizationPrefs.setAnimatedPreview(this, value)
            refreshChildren()
        }
        mainRow.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        refreshChildren()
    }
}
