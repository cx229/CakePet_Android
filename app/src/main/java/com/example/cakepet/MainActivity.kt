package com.example.cakepet

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cakepet.databinding.ActivityMainBinding
import com.example.cakepet.PetConfig

/**
 * 入口 Activity：浮窗权限引导 + 启动/召回宠物。
 * 对应 PC 端无（PC 直接运行），安卓必须做 SYSTEM_ALERT_WINDOW 引导。
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStartOrRecall() }

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
        binding.btnStartTitle.text = if (has) "启动 / 召回" else "授权浮窗权限"
        // 已授权时按宠物状态显示，不再冗余提示“浮窗权限：已授权”
        binding.tvStatus.text = when {
            !has -> "未授权"
            !isServiceRunning() -> "未启动"
            PetConfig(this).getBlocking().visible -> "已显示"
            else -> "已隐藏"
        }
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
        if (isServiceRunning()) {
            // 召回：复用 PetService 的 ACTION_RECALL
            val intent = Intent(this, PetService::class.java).apply {
                action = MenuActivity.ACTION_RECALL
            }
            startService(intent)
        } else {
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
