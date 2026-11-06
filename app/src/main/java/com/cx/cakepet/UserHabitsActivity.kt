package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Habits list follows the role Settings page. Each child uses the existing
 * BaseThresholdActivity rendering with an independent preference identity.
 */
class UserHabitsActivity : AppCompatActivity() {
    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(SettingsInterchange.exportHabits(this).toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess { toast("用户习惯已导出") }.onFailure { toast("导出失败：${it.message}") }
    }

    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                JSONObject(it.readText())
            } ?: error("无法读取文件")
        }.onSuccess { json ->
            showConfirmDialog(
                title = "导入用户习惯",
                bodyHtml = "将导入的默认值和阈值用于<b>以后新建的角色</b>，不会修改已有角色。",
                positiveText = "导入"
            ) {
                runCatching { SettingsInterchange.importHabits(this, json) }
                    .onSuccess { toast("用户习惯已导入") }
                    .onFailure { toast("导入失败：${it.message}") }
            }
        }.onFailure { toast("无法读取设置：${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_threshold_settings)
        val status = findViewById<View>(R.id.status_bar_spacer)
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        status.layoutParams.height = if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
        status.requestLayout()

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "用户习惯"
        findViewById<TextView>(R.id.tv_title_sub).text = "编辑后自动用于新建角色"
        val more = findViewById<ImageView>(R.id.btn_more)
        val menu = PopupMenuHelper(more, R.layout.popup_user_habits, mapOf(
            R.id.item_habit_import to {
                importFile.launch(arrayOf("application/json", "text/plain"))
            },
            R.id.item_habit_export to { exportFile.launch("CakePet-UserHabits.json") },
            R.id.item_habit_apply_all to {
                showConfirmDialog(
                    title = "应用全部用户习惯",
                    bodyHtml = "将全部默认值和阈值应用到<b>所有已有角色</b>？",
                    positiveText = "应用"
                ) {
                    runCatching { UserHabits.applyToExisting(this) }
                        .onSuccess { toast("已应用到 $it 个角色") }
                        .onFailure { toast("应用失败：${it.message}") }
                }
            },
            R.id.item_habit_reset to {
                showConfirmDialog(
                    title = "恢复初始用户习惯",
                    bodyHtml = "将用户习惯全部恢复为软件自带的默认值和阈值？<b>已有角色不受影响</b>。",
                    positiveText = "恢复"
                ) {
                    runCatching {
                        val bounds = PetBounds.forRole(this, UserHabits.ROLE_ID)
                        kotlinx.coroutines.runBlocking {
                            SettingsPage.entries.forEach { bounds.resetSystem(it) }
                        }
                    }.onSuccess { toast("用户习惯已恢复初始值") }
                     .onFailure { toast("恢复失败：${it.message}") }
                }
            }
        ))
        more.setOnClickListener { menu.show() }

        val container = findViewById<LinearLayout>(R.id.container)
        addGroup(container, "显示", listOf(
            Entry("主设置", "大小、点击穿透", SettingsPage.MAIN),
            Entry("显示范围", "透明度、点击范围、活动边界", SettingsPage.RANGE, false),
            Entry("显示适应", "键盘适应、旋转适应", SettingsPage.RANGE, true)
        ))
        addGroup(container, "角色行为", listOf(
            Entry("移动", "运动、抛掷与重力", SettingsPage.MOVEMENT),
            Entry("吸附边缘", "吸附、攀岩", SettingsPage.SNAP),
            Entry("碎碎念", "文字与气泡", SettingsPage.THINKING),
            Entry("投食", "投食和觅食", SettingsPage.FEEDING),
            Entry("随机动作", "随机触发与间隔", SettingsPage.RANDOM)
        ))
    }

    private data class Entry(
        val name: String, val note: String, val page: SettingsPage, val adapt: Boolean = false
    )

    private fun addGroup(container: LinearLayout, label: String, entries: List<Entry>) {
        val header = layoutInflater.inflate(R.layout.include_module_header, container, false)
        header.findViewById<TextView>(R.id.tv_module_title).text = label
        container.addView(header)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = getDrawable(R.drawable.item_divider)
            setPadding(0, dp(8), 0, dp(8))
        }
        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                minimumHeight = dp(60)
                background = getDrawable(R.drawable.settings_row_ripple)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val target = when (entry.page) {
                        SettingsPage.MAIN -> MainThresholdActivity::class.java
                        SettingsPage.SNAP -> SnapThresholdActivity::class.java
                        SettingsPage.RANGE -> RangeThresholdActivity::class.java
                        SettingsPage.THINKING -> ThinkingThresholdActivity::class.java
                        SettingsPage.RANDOM -> RandomThresholdActivity::class.java
                        SettingsPage.FEEDING -> FeedingThresholdActivity::class.java
                        SettingsPage.MOVEMENT -> MovementThresholdActivity::class.java
                    }
                    startActivity(Intent(this@UserHabitsActivity, target).apply {
                        putExtra("user_habit_mode", true)
                        if (entry.page == SettingsPage.RANGE) putExtra("adapt_page", entry.adapt)
                    })
                }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = entry.name
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.cp_title))
            })
            texts.addView(TextView(this).apply {
                text = entry.note
                textSize = 11f
                setTextColor(getColor(R.color.cp_note))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(getColor(R.color.icon_chevron))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
