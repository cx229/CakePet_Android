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

    private fun openPreviewFunction(path: List<String>, title: String) {
        startActivity(android.content.Intent(this, RolePackLinkedActivity::class.java).apply {
            putExtra("linked_global_preview", true)
            putExtra("linked_title", title)
            putStringArrayListExtra("linked_paths", ArrayList(listOf(path.joinToString("/"))))
        })
    }

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
                     selected: Int, indent: Boolean = false, note: String? = null, choose: (Int) -> Unit): SettingsRippleRow {
            val row = SettingsRippleRow(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (indent) 32 else 20), dp(8), dp(20), dp(8))
                minimumHeight = dp(60)
            }
            row.addView(caption(title, note), LinearLayout.LayoutParams(0, -2, 1f))
            val spinner = Spinner(this).apply { minimumHeight = dp(44) }
            row.addView(spinner, LinearLayout.LayoutParams(dp(148), dp(44)))
            SettingsSpinnerBinder.bind(spinner, choices, selected, choose)
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            return row
        }

        val home = module("首页")
        selector(home, "召唤位置", listOf("底部中间", "随机位置"),
            if (PersonalizationPrefs.summonPosition(this) == PersonalizationPrefs.RANDOM) 1 else 0,
            note = "每次召唤「酥」的初始位置") {
            PersonalizationPrefs.setSummonPosition(this, if (it == 1) PersonalizationPrefs.RANDOM
                else PersonalizationPrefs.BOTTOM_CENTER)
        }

        val previews = module("角色预览")
        val previewSources = listOf(
            PersonalizationPrefs.PREVIEW, PersonalizationPrefs.STANDBY,
            PersonalizationPrefs.STATIC_POOL, PersonalizationPrefs.BLANK
        )
        lateinit var refreshChildren: () -> Unit
        val previewRow = selector(previews, "动态预览",
            listOf("动画：预览", "待机池", "静止待机池", "空白"),
            previewSources.indexOf(PersonalizationPrefs.previewSource(this)).coerceAtLeast(0),
            note = "选择首页角色卡片的动画来源") { index ->
            PersonalizationPrefs.setPreviewSource(this, previewSources[index])
            refreshChildren()
        }
        // The editor link follows the selected Functions path; blank has none.
        SettingsMaterialLink.attachInline(this, previewRow, "动态预览",
            previewRow.getChildAt(1), "编辑功能/动画 ›") {
            val source = PersonalizationPrefs.previewSource(this)
            val path = PersonalizationPrefs.previewFunctionPath(source) ?: return@attachInline
            openPreviewFunction(path, when (source) {
                PersonalizationPrefs.PREVIEW -> "预览"
                PersonalizationPrefs.STANDBY -> "待机池"
                else -> "静止待机池"
            })
        }
        val previewLink = ((previewRow.getChildAt(0) as? LinearLayout)
            ?.getChildAt(0) as? LinearLayout)?.getChildAt(1)

        val patRow = selector(previews, "摸摸头", listOf("开启", "关闭"),
            if (PersonalizationPrefs.previewPat(this)) 0 else 1, true,
            note = "点击预览角色触发摸摸头") {
            PersonalizationPrefs.setPreviewPat(this, it == 0)
        }
        SettingsMaterialLink.attachInline(this, patRow, "摸摸头",
            patRow.getChildAt(1), "编辑功能/动画 ›") {
            openPreviewFunction(listOf("pat_head", "pat_head"), "摸摸头")
        }
        val modeRow = selector(previews, "召唤后", listOf("预览·常驻", "动画·已召唤"),
            if (PersonalizationPrefs.homePreviewMode(this) == PersonalizationPrefs.DYNAMIC) 1 else 0, true,
            note = "召唤后首页的预览方式") {
            PersonalizationPrefs.setHomePreviewMode(this,
                if (it == 1) PersonalizationPrefs.DYNAMIC else PersonalizationPrefs.ALWAYS)
        }
        // Same inline title action and linked editor used throughout role settings.
        SettingsMaterialLink.attachInline(this, modeRow, "召唤后",
            modeRow.getChildAt(1), "编辑功能/动画 ›") {
            startActivity(android.content.Intent(this, RolePackLinkedActivity::class.java).apply {
                putExtra("linked_global_preview", true)
                putExtra("linked_title", "已召唤")
                putStringArrayListExtra("linked_paths", arrayListOf("menu/summoned"))
            })
        }

        refreshChildren = {
            val visible = PersonalizationPrefs.previewSource(this) != PersonalizationPrefs.BLANK
            patRow.visibility = if (visible) View.VISIBLE else View.GONE
            modeRow.visibility = if (visible) View.VISIBLE else View.GONE
            previewLink?.visibility = if (visible) View.VISIBLE else View.GONE
        }
        refreshChildren()
    }
}
