package com.cx.cakepet

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
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
                val globalText = if (manifest.optBoolean("appSettingsIncluded", false))
                    "包含个性化设置、去水印设置及召唤状态"
                else "这份备份不含全局个性化和去水印设置，导入后保留当前值"
                showConfirmDialog(
                    title = "导入软件备份",
                    bodyHtml = "来源版本：$version<br/>包含：$roleText。<br/>$globalText。<br/>只覆盖当前版本能识别的内容，无法兼容的项目会跳过，其他数据保留。",
                    positiveText = "选择性导入",
                    titleColorRes = R.color.cp_danger
                ) {
                    lifecycleScope.launch {
                        val background = getSharedPreferences("cakepet_background", MODE_PRIVATE)
                        val summoned = background.getStringSet("summoned_roles", emptySet())
                            .orEmpty().toSet()
                        PetService.instance?.let { svc ->
                            RoleStore.getHomeRoles(this@GlobalSettingsActivity)
                                .forEach { svc.recall(it.instanceId) }
                        }
                        // Keep summon intent independent of stopping the old
                        // service; imported settings can then override it safely.
                        background.edit().putStringSet("summoned_roles", summoned).commit()
                        stopService(Intent(this@GlobalSettingsActivity, PetService::class.java))
                        val result = withContext(Dispatchers.IO) {
                            runCatching { SoftwareBackup.restore(this@GlobalSettingsActivity, uri) }
                        }
                        Toast.makeText(this@GlobalSettingsActivity,
                            result.fold({ it }, { "恢复失败：${it.message}" }),
                            Toast.LENGTH_LONG).show()
                        if (result.isFailure) {
                            background.edit().putStringSet("summoned_roles", summoned).commit()
                        }
                        // Neither success nor failure should leave a previously
                        // running pet service permanently stopped.
                        if (summoned.isNotEmpty() || result.isSuccess) {
                            startForegroundService(Intent(this@GlobalSettingsActivity, PetService::class.java))
                        }
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
                val levelSpinner = findViewById<android.widget.Spinner>(R.id.sp_import_watermark_level)
                val watermarkLevels = listOf(
                    ImportWatermark.Level.LOW,
                    ImportWatermark.Level.MEDIUM,
                    ImportWatermark.Level.HIGH
                )
                SettingsSpinnerBinder.bind(levelSpinner, listOf("低", "中", "高"),
                    watermarkLevels.indexOf(ImportWatermark.level(this)).coerceAtLeast(0)) { index ->
                    ImportWatermark.setLevel(this, watermarkLevels[index])
                }
                findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_import_watermark).apply {
                    isChecked = ImportWatermark.enabled(this@GlobalSettingsActivity)
                    watermarkLevelRow.visibility = if (isChecked) View.VISIBLE else View.GONE
                    setOnCheckedChangeListener { _, checked ->
                        ImportWatermark.setEnabled(this@GlobalSettingsActivity, checked)
                        watermarkLevelRow.visibility = if (checked) View.VISIBLE else View.GONE
                    }
                }
                findViewById<View>(R.id.row_battery_optimization).setOnClickListener {
                    openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
                findViewById<View>(R.id.row_background_management).setOnClickListener {
                    showConfirmDialog(
                        title = "后台运行设置",
                        bodyHtml = "部分手机需要在系统设置中允许 CakePet 后台活动、自启动，或将电池策略设为「无限制」。这些项目由手机系统管理。",
                        positiveText = "前往应用设置"
                    ) { openAppInfo() }
                }
                findViewById<View>(R.id.row_personalization_settings).setOnClickListener {
                    startActivity(Intent(this, PersonalizationSettingsActivity::class.java))
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
                // Permission is managed by Android; explain the purpose before opening system settings.
                accessibilityRow.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("无障碍权限说明")
                        .setMessage(
                            "部分设备需要借助无障碍服务显示和操作桌面角色，以改善角色的显示效果与触摸交互。\n\n" +
                                "你可以在系统设置中开启或关闭该权限。"
                        )
                        .setNegativeButton("取消", null)
                        .setPositiveButton("前往设置") { _, _ ->
                            try {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (e: android.content.ActivityNotFoundException) {
                                Toast.makeText(this, "无法打开系统无障碍设置", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .show()
                }
                findViewById<TextView>(R.id.tv_next).text = "调试"
                nextRow.setOnClickListener { openPage(PAGE_DEBUG) }
            }
            PAGE_DEBUG -> {
                findViewById<View>(R.id.background_group).visibility = View.GONE
                findViewById<View>(R.id.system_section_heading).visibility = View.GONE
                // With prior modules hidden, this is the first visible section.
                (findViewById<View>(R.id.system_section_container).layoutParams as android.widget.LinearLayout.LayoutParams)
                    .apply { topMargin = 0 }
                findViewById<View>(R.id.row_import_watermark).visibility = View.GONE
                findViewById<View>(R.id.row_import_watermark_level).visibility = View.GONE
                findViewById<View>(R.id.backup_group).visibility = View.GONE
                accessibilityRow.visibility = View.GONE
                findViewById<TextView>(R.id.tv_next).text = "无障碍点击测试"
                nextRow.setOnClickListener { openPage(PAGE_ACCESSIBILITY_TEST) }
            }
            else -> {
                findViewById<View>(R.id.background_group).visibility = View.GONE
                // Hide the entire unused module, including its outer margin.
                findViewById<View>(R.id.system_section_container).visibility = View.GONE
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
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            findViewById<TextView>(R.id.tv_battery_status).text =
                if (pm.isIgnoringBatteryOptimizations(packageName)) "不受电池优化限制" else "受电池优化限制"
            val svc = PetService.instance
            findViewById<TextView>(R.id.tv_background_status).text = when {
                svc == null -> "服务未运行"
                svc.activeRoleCount() == 0 -> "运行中 · 无桌宠"
                else -> "运行中 · ${svc.activeRoleCount()} 个桌宠"
            }
        }
    }

    private fun openAppInfo() {
        openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"))
    }

    private fun openSystemSettings(action: String, uri: Uri? = null) {
        try {
            startActivity(Intent(action).apply { data = uri })
        } catch (_: Exception) {
            if (action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                }
            } else Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
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
