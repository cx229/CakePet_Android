package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.Rect

/** 与播放对象无关的图片处理：透明像素范围、后续统一裁剪和预览共用。 */
object ModeImageProcessing {
    fun alphaBounds(bitmap: Bitmap, threshold: Int = 0): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val row = IntArray(w)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > threshold) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }
}
