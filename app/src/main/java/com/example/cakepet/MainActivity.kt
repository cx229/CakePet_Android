package com.example.cakepet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cakepet.databinding.ActivityMainBinding

/**
 * 入口 Activity：浮窗权限引导 + 启动/召回宠物。
 * 对应 PC 端无（PC 直接运行），安卓必须做 SYSTEM_ALERT_WINDOW 引导。
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAndStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { checkAndStart() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val has = hasOverlayPermission()
        binding.tvStatus.text = if (has) "浮窗权限：已授权" else "浮窗权限：未授权（需授权）"
        binding.btnStart.text = if (has) "启动 / 召回宠物" else "授权浮窗权限"
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun checkAndStart() {
        if (!hasOverlayPermission()) {
            // 引导到"在其他应用上层显示"设置页
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        // 启动浮窗服务
        val intent = Intent(this, PetService::class.java)
        startForegroundService(intent)
        binding.tvStatus.text = "宠物已启动（在屏幕顶层）"
    }
}
