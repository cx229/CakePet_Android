package com.cx.cakepet

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用级页面：全局设置 -> 调试 -> 无障碍点击测试。
 * 暂不启动任何悬浮窗或触摸实验；所有页面不依赖角色 ID。
 */
class GlobalSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_SETTINGS = "settings"
        const val PAGE_DEBUG = "debug"
        const val PAGE_ACCESSIBILITY_TEST = "accessibility_test"
    }

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SoftwareBackup.export(this@GlobalSettingsActivity, uri) }
            }
            Toast.makeText(this@GlobalSettingsActivity,
                result.fold({ "软件备份已导出" }, { "导出失败：${it.message}" }),
                Toast.LENGTH_LONG).show()
        }
    }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            // Do not tear down a running pet when a ZIP is invalid.
            val preview = withContext(Dispatchers.IO) {
                runCatching { SoftwareBackup.inspect(this@GlobalSettingsActivity, uri) }
            }
            preview.onFailure {
                Toast.makeText(this@GlobalSettingsActivity,
                    "备份校验失败：${it.message}", Toast.LENGTH_LONG).show()
            }.onSuccess { manifest ->
                val version = manifest.optString("appVersion", "未知")
                val summary = manifest.optJSONObject("summary")
                val roles = summary?.optInt("roleInstances", -1) ?: -1
                val roleText = if (roles >= 0) "$roles 个角色实例" else "角色实例"
                showConfirmDialog(
                    title = "导入软件备份",
                    bodyHtml = "来源版本：$version<br/>包含：$roleText。<br/>将<b>覆盖当前素材包、实例及持久化设置</b>，建议先备份当前数据。",
                    positiveText = "覆盖恢复",
                    titleColorRes = R.color.cp_danger
                ) {
                    lifecycleScope.launch {
                        PetService.instance?.let { svc ->
                            RoleStore.getHomeRoles(this@GlobalSettingsActivity)
                                .forEach { svc.recall(it.instanceId) }
                        }
                        stopService(Intent(this@GlobalSettingsActivity, PetService::class.java))
                        val result = withContext(Dispatchers.IO) {
                            runCatching { SoftwareBackup.restore(this@GlobalSettingsActivity, uri) }
                        }
                        Toast.makeText(this@GlobalSettingsActivity,
                            result.fold({ it }, { "恢复失败：${it.message}" }),
                            Toast.LENGTH_LONG).show()
                        if (result.isSuccess) finish()
                    }
                }
            }
        }
    }

    private var page = PAGE_SETTINGS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)
        page = when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_DEBUG -> PAGE_DEBUG
            PAGE_ACCESSIBILITY_TEST -> PAGE_ACCESSIBILITY_TEST
            else -> PAGE_SETTINGS
        }

        val spacer = findViewById<View>(R.id.status_bar_spacer)
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        spacer.layoutParams.height = if (resId > 0) resources.getDimensionPixelSize(resId)
            else (resources.displayMetrics.density * 24f).toInt()
        spacer.requestLayout()

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = when (page) {
            PAGE_DEBUG -> "调试"
            PAGE_ACCESSIBILITY_TEST -> "无障碍点击测试"
            else -> "全局设置"
        }

        val accessibilityRow = findViewById<View>(R.id.row_accessibility)
        val nextRow = findViewById<View>(R.id.row_next)
        when (page) {
            PAGE_SETTINGS -> {
                val watermarkLevelRow = findViewById<View>(R.id.row_import_watermark_level)
                val levelGroup = findViewById<android.widget.RadioGroup>(R.id.rg_import_watermark_level)
                levelGroup.check(when (ImportWatermark.level(this)) {
                    ImportWatermark.Level.LOW -> R.id.rb_watermark_low
                    ImportWatermark.Level.MEDIUM -> R.id.rb_watermark_medium
                    ImportWatermark.Level.HIGH -> R.id.rb_watermark_high
                })
                levelGroup.setOnCheckedChangeListener { _, checkedId ->
                    val strength = when (checkedId) {
                        R.id.rb_watermark_low -> ImportWatermark.Level.LOW
                        R.id.rb_watermark_high -> ImportWatermark.Level.HIGH
                        else -> ImportWatermark.Level.MEDIUM
                    }
                    ImportWatermark.setLevel(this, strength)
                }
                findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_import_watermark).apply {
                    isChecked = ImportWatermark.enabled(this@GlobalSettingsActivity)
                    watermarkLevelRow.visibility = if (isChecked) View.VISIBLE else View.GONE
                    setOnCheckedChangeListener { _, checked ->
                        ImportWatermark.setEnabled(this@GlobalSettingsActivity, checked)
                        watermarkLevelRow.visibility = if (checked) View.VISIBLE else View.GONE
                    }
                }
                findViewById<View>(R.id.row_user_habits).setOnClickListener {
                    startActivity(Intent(this, UserHabitsActivity::class.java))
                }
                findViewById<View>(R.id.row_backup_export).setOnClickListener {
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    exportBackup.launch("CakePet-Backup-$timestamp.zip")
                }
                findViewById<View>(R.id.row_backup_import).setOnClickListener {
                    importBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }
                // 状态只读；不把系统权限伪装成可由应用切换的开关。
                findViewById<TextView>(R.id.tv_next).text = "调试"
                nextRow.setOnClickListener { openPage(PAGE_DEBUG) }
            }
            PAGE_DEBUG -> {
                findViewById<View>(R.id.row_import_watermark).visibility = View.GONE
                findViewById<View>(R.id.row_import_watermark_level).visibility = View.GONE
                findViewById<View>(R.id.backup_group).visibility = View.GONE
                accessibilityRow.visibility = View.GONE
                findViewById<TextView>(R.id.tv_next).text = "无障碍点击测试"
                nextRow.setOnClickListener { openPage(PAGE_ACCESSIBILITY_TEST) }
            }
            else -> {
                findViewById<View>(R.id.row_import_watermark).visibility = View.GONE
                findViewById<View>(R.id.row_import_watermark_level).visibility = View.GONE
                findViewById<View>(R.id.backup_group).visibility = View.GONE
                // 实验尚未实现，按需求保留空白内容区。
                accessibilityRow.visibility = View.GONE
                nextRow.visibility = View.GONE
                findViewById<View>(R.id.test_buttons).visibility = View.VISIBLE
                findViewById<View>(R.id.btn_create_test).setOnClickListener {
                    launchTest(CakePetAccessibilityService.MODE_SPLIT)
                }
                findViewById<View>(R.id.btn_passive_test).setOnClickListener {
                    launchTest(CakePetAccessibilityService.MODE_PASS_THROUGH)
                }
                findViewById<View>(R.id.btn_full_test).setOnClickListener {
                    launchTest(CakePetAccessibilityService.MODE_FULL_TOUCH)
                }
                findViewById<View>(R.id.btn_close_test).setOnClickListener {
                    CakePetAccessibilityService.active?.hideHitTest()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (page == PAGE_SETTINGS) {
            val expected = ComponentName(this, CakePetAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.split(":")?.any { it.equals(expected, ignoreCase = true) } == true
            findViewById<TextView>(R.id.tv_accessibility_status).text =
                if (enabled) "已开启" else "未开启"
        }
    }

    private fun launchTest(mode: Int) {
        val service = CakePetAccessibilityService.active
        if (service == null) {
            AlertDialog.Builder(this)
                .setMessage("你现在没有无障碍权限，或无障碍服务尚未连接。")
                .setPositiveButton("确定", null).show()
        } else if (!service.showHitTest(mode)) {
            AlertDialog.Builder(this)
                .setMessage("无障碍测试窗口创建失败，请检查系统限制。")
                .setPositiveButton("确定", null).show()
        }
    }

    private fun openPage(destination: String) {
        startActivity(Intent(this, GlobalSettingsActivity::class.java).apply {
            putExtra(EXTRA_PAGE, destination)
        })
    }
}
