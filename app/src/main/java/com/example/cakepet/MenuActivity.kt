package com.example.cakepet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 长按弹出的菜单（对应 PC 托盘右键菜单）。
 * 以 BottomSheetDialog 呈现：摸头彩蛋 / 跳跃 / 设置 / 隐藏(停止浮窗) / 退出。
 */
class MenuActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSheet()
    }

    private fun showSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_menu, null)
        dialog.setContentView(view)

        view.findViewById<android.widget.TextView>(R.id.menu_pat).setOnClickListener {
            sendToService(ACTION_PAT)
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.menu_jump).setOnClickListener {
            sendToService(ACTION_JUMP)
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.menu_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        view.findViewById<android.widget.TextView>(R.id.menu_hide).setOnClickListener {
            dialog.dismiss()
            // 隐藏 = 停止浮窗服务
            stopService(Intent(this, PetService::class.java))
            finish()
        }
        view.findViewById<android.widget.TextView>(R.id.menu_exit).setOnClickListener {
            dialog.dismiss()
            stopService(Intent(this, PetService::class.java))
            finish()
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, PetService::class.java).apply {
            this.action = action
        }
        // Service 已在运行，用 startService 传递 action（onStartCommand 接收）
        startService(intent)
    }

    companion object {
        const val ACTION_PAT = "com.example.cakepet.ACTION_PAT"
        const val ACTION_JUMP = "com.example.cakepet.ACTION_JUMP"
    }
}
