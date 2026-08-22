package com.example.cakepet

import kotlin.math.abs

/**
 * 物理引擎（移植自 PC 端 speed_util.cal_throw_rebound_offset + MouseFollowController 抛掷逻辑）。
 *
 * 关键设计：
 * 1. 速度 vx/vy（像素/秒），每帧 dt 推进：offset += v * dt；v += gravity * dt
 * 2. 四边界反弹：碰到开启重力的边界则 v = -v * reboundRatio，并带 sub-pixel 余数累积
 *    （对应 PC 的 QPointF remainder，避免低速抖动丢帧）
 * 3. 四边重力：gravityX/gravityY 可正可负/零；由各边开关决定加速度方向
 * 4. 兜底：速度低于阈值且无重力时自动静止，省电
 */
class PetPhysics {

    // 位置（左上角坐标，像素）
    var x = 0f
    var y = 0f

    // 速度（像素/秒）
    var vx = 0f
    var vy = 0f

    // sub-pixel 余数累积（对应 PC QPointF remainder）
    private var remX = 0f
    private var remY = 0f

    // 屏幕边界（宠物可活动区域，需扣除宠物尺寸）
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxY = 0f

    // 配置（实时更新）
    var gravity = 1500f
    var reboundRatio = 0.7f
    var gravityTop = false
    var gravityBottom = true
    var gravityLeft = false
    var gravityRight = false

    // 静止阈值：速度低于此值且无重力作用时停止
    private val stillThreshold = 5f

    var isDragging = false

    /** 外部设置速度（抛掷），对应 PC 的 set_status(throw) */
    fun throwWith(vx: Float, vy: Float) {
        this.vx = vx
        this.vy = vy
    }

    /** 是否已基本静止（用于决定是否继续动画） */
    fun isSettled(): Boolean {
        val anyGravity = gravity != 0f && (gravityTop || gravityBottom || gravityLeft || gravityRight)
        if (anyGravity) return false
        return abs(vx) < stillThreshold && abs(vy) < stillThreshold
    }

    /**
     * 单帧推进（对应 PC cal_throw_rebound_offset）。
     * interval 单位秒。
     */
    fun step(interval: Float) {
        if (isDragging) return

        // --- 重力加速度（四边重力）---
        // 底边开启：向下加速
        if (gravityBottom) vy += gravity * interval
        // 顶边开启：向上加速（负方向）
        if (gravityTop) vy -= gravity * interval
        // 右边开启：向右加速
        if (gravityRight) vx += gravity * interval
        // 左边开启：向左加速（负方向）
        if (gravityLeft) vx -= gravity * interval

        // --- X 轴移动 + 左右边界反弹 ---
        stepAxisX(interval)
        // --- Y 轴移动 + 上下边界反弹 ---
        stepAxisY(interval)

        // 兜底：无重力且速度极低时清零，避免永久微抖
        val anyGravity = gravityBottom || gravityTop || gravityLeft || gravityRight
        if (!anyGravity) {
            if (abs(vx) < stillThreshold) vx = 0f
            if (abs(vy) < stillThreshold) vy = 0f
        }
    }

    private fun stepAxisX(interval: Float) {
        if (vx == 0f && remX == 0f) return
        val move = vx * interval
        var next = x + move + remX

        if (vx > 0) {
            // 向右
            if (next >= maxX) {
                if (gravityRight) {
                    // 右边重力开启：反弹（对应 PC 四边反弹）
                    val realGap = maxX - x - remX
                    val extra = move - realGap
                    x = maxX
                    remX = 0f
                    vx = -vx * reboundRatio
                    remX += extra
                    val after = x + vx * interval + remX
                    if (after < minX) {
                        x = minX
                        remX = 0f
                        vx = -vx * reboundRatio
                    } else {
                        x = after
                        remX = x - x.toInt()
                        x = x.toInt().toFloat()
                    }
                } else {
                    // 右边无重力：停靠该边，水平速度清零
                    x = maxX
                    remX = 0f
                    vx = 0f
                }
            } else {
                x = next
                remX = x - x.toInt()
                x = x.toInt().toFloat()
            }
        } else if (vx < 0) {
            // 向左
            if (next <= minX) {
                if (gravityLeft) {
                    val realGap = x + remX - minX
                    val extra = abs(move) - realGap
                    x = minX
                    remX = 0f
                    vx = -vx * reboundRatio
                    remX -= extra
                    val after = x + vx * interval + remX
                    if (after > maxX) {
                        x = maxX
                        remX = 0f
                        vx = -vx * reboundRatio
                    } else {
                        x = after
                        remX = x - x.toInt()
                        x = x.toInt().toFloat()
                    }
                } else {
                    x = minX
                    remX = 0f
                    vx = 0f
                }
            } else {
                x = next
                remX = x - x.toInt()
                x = x.toInt().toFloat()
            }
        }
        // 夹紧边界（安全）
        if (x < minX) { x = minX; remX = 0f }
        if (x > maxX) { x = maxX; remX = 0f }
    }

    private fun stepAxisY(interval: Float) {
        if (vy == 0f && remY == 0f) return
        val move = vy * interval
        var next = y + move + remY

        if (vy > 0) {
            // 向下
            if (next >= maxY) {
                if (gravityBottom) {
                    val realGap = maxY - y - remY
                    val extra = move - realGap
                    y = maxY
                    remY = 0f
                    vy = -vy * reboundRatio
                    remY += extra
                    val after = y + vy * interval + remY
                    if (after < minY) {
                        y = minY
                        remY = 0f
                        vy = -vy * reboundRatio
                    } else {
                        y = after
                        remY = y - y.toInt()
                        y = y.toInt().toFloat()
                    }
                } else {
                    y = maxY
                    remY = 0f
                    vy = 0f
                }
            } else {
                y = next
                remY = y - y.toInt()
                y = y.toInt().toFloat()
            }
        } else if (vy < 0) {
            // 向上
            if (next <= minY) {
                if (gravityTop) {
                    val realGap = y + remY - minY
                    val extra = abs(move) - realGap
                    y = minY
                    remY = 0f
                    vy = -vy * reboundRatio
                    remY -= extra
                    val after = y + vy * interval + remY
                    if (after > maxY) {
                        y = maxY
                        remY = 0f
                        vy = -vy * reboundRatio
                    } else {
                        y = after
                        remY = y - y.toInt()
                        y = y.toInt().toFloat()
                    }
                } else {
                    y = minY
                    remY = 0f
                    vy = 0f
                }
            } else {
                y = next
                remY = y - y.toInt()
                y = y.toInt().toFloat()
            }
        }
        if (y < minY) { y = minY; remY = 0f }
        if (y > maxY) { y = maxY; remY = 0f }
    }

    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        this.minX = minX
        this.minY = minY
        this.maxX = maxX
        this.maxY = maxY
        // 夹紧当前位置
        if (x < minX) x = minX
        if (x > maxX) x = maxX
        if (y < minY) y = minY
        if (y > maxY) y = maxY
    }
}
