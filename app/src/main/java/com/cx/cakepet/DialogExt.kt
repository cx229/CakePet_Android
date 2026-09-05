package com.cx.cakepet

import android.app.Dialog
import android.text.Html
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 通用确认弹窗：圆角卡片 + 三级字号（标题 18sp 粗 / 正文 12sp / 小字 8sp 灰）。
 *
 * 注意：用普通 Dialog + 透明窗口背景实现，而非 MaterialAlertDialogBuilder。
 * 因为 MaterialAlertDialogBuilder.setView() 仍会在自定义视图之后绘制 Material 自带的对话气泡面板背景，
 * 导致卡片与圆角面板两层叠加、圆角异常；普通 Dialog 仅由 MaterialCardView 提供圆角与阴影，干净可控。
 *
 * @param title        标题文字（居中）
 * @param bodyHtml     正文，支持 HTML 加粗（如 "将<b>本页</b>的所有设置…"）
 * @param positiveText 确认按钮文案（如 "恢复" / "重置"）
 * @param onPositive   点击确认后的执行逻辑（弹窗先 dismiss 再回调）
 * @param subText      小字说明（8sp 灰，置于最底部居中），可空；为空则不显示
 */
fun AppCompatActivity.showConfirmDialog(
    title: String,
    bodyHtml: String,
    positiveText: String,
    subText: String? = null,
    onPositive: () -> Unit
) {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
    val tvTitle = view.findViewById<TextView>(R.id.tv_title)
    val tvBody = view.findViewById<TextView>(R.id.tv_body)
    val tvSub = view.findViewById<TextView>(R.id.tv_sub)
    val btnNegative = view.findViewById<Button>(R.id.btn_negative)
    val btnPositive = view.findViewById<Button>(R.id.btn_positive)

    tvTitle.text = title
    tvBody.text = Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY)
    if (subText.isNullOrEmpty()) {
        tvSub.visibility = android.view.View.GONE
    } else {
        tvSub.visibility = android.view.View.VISIBLE
        tvSub.text = subText
    }
    btnPositive.text = positiveText
    btnNegative.text = "取消"

    val dialog = Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.setContentView(view)
    dialog.setCancelable(true)

    btnNegative.setOnClickListener { dialog.dismiss() }
    btnPositive.setOnClickListener {
        dialog.dismiss()
        onPositive()
    }
    dialog.show()
}
