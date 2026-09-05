package com.cx.cakepet

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.cx.cakepet.databinding.ActivityMainBinding
import com.cx.cakepet.PetConfig
import com.cx.cakepet.AppVersion

/**
 * 入口 Activity：浮窗权限引导 + 启动/召回宠物。
 * 对应 PC 端无（PC 直接运行），安卓必须做 SYSTEM_ALERT_WINDOW 引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStartOrRecall() }

    /** 读取 PetService 持久化的退出意图，避免进程重启后误把已退出的宠物复活。 */
    private fun wasExited(): Boolean {
        val prefs = getSharedPreferences("cakepet_exit", MODE_PRIVATE)
        return prefs.getBoolean("exiting", false)
    }

    /** 用户明确启动宠物时，清除先前的退出意图。 */
    private fun clearExited() {
        getSharedPreferences("cakepet_exit", MODE_PRIVATE)
            .edit().putBoolean("exiting", false).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { onStartOrRecall() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val has = hasOverlayPermission()
        val running = isServiceRunning()
        // 已退出过：视为未运行，按钮提示“启动”，不误复活宠物。
        val exited = wasExited()
        // 按钮文字：未授权→引导授权；已授权未运行→启动；已运行→召回
        binding.btnStartTitle.text = when {
            !has -> "授权浮窗权限"
            !running || exited -> "启动"
            else -> "召回"
        }
        // 状态文本仅提示权限问题，不再显示“已显示/未启动”等冗余状态
        binding.tvStatus.text = if (!has) "未授权浮窗权限" else ""
        // 首页作者署名后追加全局版本号（带 v 前缀），用“ · ”（前后空格）分隔
        binding.tvAuthor.text = "初心丶CX  ·  v${AppVersion.name}"
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    /** 按钮：未运行则启动服务；已运行则召回（归位底部中心、静止、显示 sit-calm） */
    private fun onStartOrRecall() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        if (isServiceRunning() && !wasExited()) {
            // 召回：复用 PetService 的 ACTION_RECALL
            val intent = Intent(this, PetService::class.java).apply {
                action = MenuActivity.ACTION_RECALL
            }
            startService(intent)
        } else {
            // 未运行（含此前已退出）：用户明确启动，先清除退出意图，再启动前台服务。
            clearExited()
            startForegroundService(Intent(this, PetService::class.java))
        }
        updateStatus()
    }

    /** 判断 PetService 是否正在运行 */
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == PetService::class.java.name }
    }
}
