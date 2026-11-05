package com.cx.cakepet

import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Per-field personal defaults. Values can be edited while disabled; only
 * selected fields are applied to NEW instances. No existing role is changed.
 */
class UserHabitsActivity : AppCompatActivity() {
    private lateinit var holder: LinearLayout
    private var section = "config"
    private lateinit var data: JSONObject

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(SettingsInterchange.exportHabits(this).toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess { notice("用户习惯已导出") }
            .onFailure { notice("导出失败：${it.message}") }
    }
    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                JSONObject(it.readText())
            } ?: error("无法读取文件")
            showConfirmDialog("导入用户习惯",
                "将替换目前保存的用户习惯，不会修改已有角色。", "导入") {
                runCatching {
                    SettingsInterchange.importHabits(this, json)
                    data = UserHabits.load(this)
                    render()
                }.onSuccess { notice("用户习惯已导入") }
                    .onFailure { notice("导入失败：${it.message}") }
            }
        }.onFailure { notice("无法读取设置：${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = UserHabits.load(this)
        // Materialize effective defaults via existing config validators, rather
        // than maintaining a second field/default mapping in the habits page.
        runBlocking {
            val config = PetConfig.forRole(this@UserHabitsActivity, RoleRegistry.DEFAULT.id)
            config.update { it }
            val bounds = PetBounds.forRole(this@UserHabitsActivity, RoleRegistry.DEFAULT.id)
            SettingsPage.entries.forEach { page ->
                bounds.updateBounds(page, bounds.getBlocking(page))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cp_bg))
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cp_bg))
        }
        val statusHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(24)
        outer.addView(View(this).apply { setBackgroundColor(getColor(R.color.cp_status_bar)) },
            LinearLayout.LayoutParams(-1, statusHeight))
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { finish() }
            contentDescription = "返回"
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "用户习惯"
            textSize = 20f
            setTextColor(getColor(R.color.cp_title))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        outer.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(info("仅作用于之后新建的角色；取消勾选会保留已填写的值。"))
        val operations = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        operations.addView(button("导入设置") { importFile.launch(arrayOf("application/json", "text/plain")) },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        operations.addView(button("导出设置") {
            exportFile.launch("CakePet-UserHabits.json")
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(operations)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(button("角色设置") { section = "config"; render() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(button("默认值与阈值") { section = "bounds"; render() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(tabs)
        holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(holder) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        render()
    }

    private fun render() {
        holder.removeAllViews()
        val source = runBlocking {
            if (section == "config")
                PetConfig.forRole(this@UserHabitsActivity, RoleRegistry.DEFAULT.id).exportSettings()
            else PetBounds.forRole(this@UserHabitsActivity, RoleRegistry.DEFAULT.id).exportSettings()
        }
        val chosen = data.optJSONObject(section) ?: JSONObject().also { data.put(section, it) }
        val selected = UserHabits.enabled(data, section)
        holder.addView(info(if (section == "config") "选择新角色要沿用的设置项"
            else "选择新角色的默认值、上下限和选项"))
        for (key in PreferenceCodec.entries(source)) {
            if (section == "config" && key in setOf("sprite_pack", "use_new_sprite", "visible")) continue
            val existing = chosen.optJSONObject(key) ?: source.optJSONObject(key) ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            val check = CheckBox(this).apply {
                text = labelFor(key)
                textSize = 13f
                setTextColor(getColor(R.color.cp_title))
                isChecked = key in selected
                setOnCheckedChangeListener { _, checked ->
                    if (!chosen.has(key)) chosen.put(key, existing)
                    UserHabits.setEnabled(data, section, key, checked)
                    save()
                }
            }
            row.addView(check)
            row.addView(info(key))
            when (existing.optString("type")) {
                "boolean" -> {
                    row.addView(Switch(this).apply {
                        text = "设置值"
                        textSize = 12f
                        isChecked = existing.optBoolean("value")
                        setOnCheckedChangeListener { _, checked ->
                            chosen.put(key, JSONObject().put("type", "boolean").put("value", checked))
                            save()
                        }
                    })
                }
                "string" -> {
                    val options = optionsFor(key)
                    if (options != null) {
                        val values = options.map { it.second }
                        val spinner = Spinner(this)
                        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                            options.map { it.first })
                        val current = existing.optString("value")
                        spinner.setSelection(values.indexOf(current).coerceAtLeast(0), false)
                        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                if (current != values[position] || chosen.has(key)) {
                                    chosen.put(key, JSONObject().put("type", "string").put("value", values[position]))
                                    save()
                                }
                            }
                        }
                        row.addView(spinner)
                    } else row.addView(valueInput(key, chosen, existing))
                }
                else -> row.addView(valueInput(key, chosen, existing))
            }
            holder.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun valueInput(key: String, chosen: JSONObject, old: JSONObject) =
        EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setText(PreferenceCodec.displayValue(old))
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, focused ->
                if (!focused) commit(key, this, chosen, old)
            }
            setOnEditorActionListener { _, _, _ ->
                commit(key, this, chosen, old)
                clearFocus()
                true
            }
        }

    private fun optionsFor(key: String): List<Pair<String, String>>? {
        if (key == "ime_mode" || key.endsWith("_imeMode_default"))
            return AppBounds.KEYBOARD_MODES
        if (key == "rotation_adapt_mode")
            return AppBounds.RANGE_CHOICES.first { it.key == "rotationAdaptMode" }.options
        if (key == "interaction_mode")
            return AppBounds.RANGE_CHOICES.first { it.key == "interactionMode" }.options
        if (section == "bounds") {
            for (page in SettingsPage.entries) {
                if (!key.startsWith(page.tag + "_")) continue
                val choice = PetBounds.forRole(this, RoleRegistry.DEFAULT.id).systemItems(page)
                    .choices.firstOrNull { key == "${page.tag}_${it.key}_default" }
                if (choice != null) return choice.options
            }
        }
        return null
    }

    private fun commit(key: String, input: EditText, chosen: JSONObject, old: JSONObject) {
        runCatching {
            val parsed = PreferenceCodec.editable(old.getString("type"), input.text.toString())
            chosen.put(key, parsed)
            save()
        }.onFailure { input.error = "格式不正确：${it.message}" }
    }
    private fun save() = UserHabits.save(this, data)
    private fun labelFor(key: String): String {
        val names = mapOf(
            "scale" to "大小", "alpha" to "透明度", "gravity" to "重力强度",
            "move_enabled" to "移动总开关", "throw_enabled" to "抛掷",
            "click_through" to "点击穿透", "snap_enabled" to "边缘吸附",
            "walk_speed" to "移动速度", "max_speed" to "最大速度",
            "thinking_enabled" to "碎碎念", "thinking_color" to "碎碎念文字颜色",
            "thinking_bg_color" to "气泡背景色", "thinking_text_size" to "文字大小",
            "feeding_enabled" to "投食", "pet_count" to "宠物数量",
            "random_enabled" to "随机动作", "wall_climb" to "攀爬",
            "ime_mode" to "键盘适应方式", "rotation_adapt_mode" to "旋转适应方式")
        names[key]?.let { return it }
        if (section == "bounds") {
            for (page in SettingsPage.entries) {
                val prefix = page.tag + "_"
                if (!key.startsWith(prefix)) continue
                val rest = key.removePrefix(prefix)
                val catalog = PetBounds.forRole(this, RoleRegistry.DEFAULT.id).systemItems(page)
                val items = catalog.sliders.map { it.key to it.label } +
                    catalog.duals.map { it.key to it.label } +
                    catalog.switches.map { it.key to it.label } +
                    catalog.choices.map { it.key to it.label } +
                    catalog.colors.map { it.key to it.label }
                items.firstOrNull { rest.startsWith(it.first + "_") }?.let { pair ->
                    return pair.second + " · " + rest.removePrefix(pair.first + "_")
                }
            }
        }
        return key.replace('_', ' ')
    }
    private fun button(text: String, act: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { act() }
    }
    private fun info(message: String) = TextView(this).apply {
        text = message
        textSize = 11f
        setTextColor(getColor(R.color.cp_sub))
        gravity = Gravity.START
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun notice(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
