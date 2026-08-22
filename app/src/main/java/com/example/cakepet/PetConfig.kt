package com.example.cakepet

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 全局配置（对应 PC 端 configs.py / config.yaml）。
 * 使用 DataStore 持久化，浮窗 Service 实时读取。
 */
data class PetConfigData(
    val scale: Float = 1.0f,          // 大小系数（对应 PC scale）
    val gravity: Float = 1500f,       // 重力强度（像素/秒^2，对应 PC gravity）
    val reboundRatio: Float = 0.7f,   // 反弹系数（对应 PC rebound_ratio）
    val walkSpeed: Float = 200f,      // 游走速度（对应 PC walk）
    val followSpeed: Float = 400f,    // 拖动尾随速度（安卓新增，替代鼠标跟随）
    // 四边重力开关（安卓新增需求）
    val gravityTop: Boolean = false,
    val gravityBottom: Boolean = true,
    val gravityLeft: Boolean = false,
    val gravityRight: Boolean = false,
    val enabled: Boolean = true       // 浮窗总开关
)

private val Context.dataStore by preferencesDataStore(name = "pet_config")

class PetConfig(private val context: Context) {

    private val KEY_SCALE = floatPreferencesKey("scale")
    private val KEY_GRAVITY = floatPreferencesKey("gravity")
    private val KEY_REBOUND = floatPreferencesKey("rebound_ratio")
    private val KEY_WALK = floatPreferencesKey("walk_speed")
    private val KEY_FOLLOW = floatPreferencesKey("follow_speed")
    private val KEY_TOP = booleanPreferencesKey("gravity_top")
    private val KEY_BOTTOM = booleanPreferencesKey("gravity_bottom")
    private val KEY_LEFT = booleanPreferencesKey("gravity_left")
    private val KEY_RIGHT = booleanPreferencesKey("gravity_right")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")

    val configFlow: Flow<PetConfigData> = context.dataStore.data.map { prefs ->
        PetConfigData(
            scale = prefs[KEY_SCALE] ?: 1.0f,
            gravity = prefs[KEY_GRAVITY] ?: 1500f,
            reboundRatio = prefs[KEY_REBOUND] ?: 0.7f,
            walkSpeed = prefs[KEY_WALK] ?: 200f,
            followSpeed = prefs[KEY_FOLLOW] ?: 400f,
            gravityTop = prefs[KEY_TOP] ?: false,
            gravityBottom = prefs[KEY_BOTTOM] ?: true,
            gravityLeft = prefs[KEY_LEFT] ?: false,
            gravityRight = prefs[KEY_RIGHT] ?: false,
            enabled = prefs[KEY_ENABLED] ?: true
        )
    }

    suspend fun update(block: (PetConfigData) -> PetConfigData) {
        val current = configFlow.first()
        val updated = block(current)
        context.dataStore.edit { prefs ->
            prefs[KEY_SCALE] = updated.scale
            prefs[KEY_GRAVITY] = updated.gravity
            prefs[KEY_REBOUND] = updated.reboundRatio
            prefs[KEY_WALK] = updated.walkSpeed
            prefs[KEY_FOLLOW] = updated.followSpeed
            prefs[KEY_TOP] = updated.gravityTop
            prefs[KEY_BOTTOM] = updated.gravityBottom
            prefs[KEY_LEFT] = updated.gravityLeft
            prefs[KEY_RIGHT] = updated.gravityRight
            prefs[KEY_ENABLED] = updated.enabled
        }
    }

    // 阻塞式读取初始值（Service 启动时用）
    fun getBlocking(): PetConfigData {
        return try {
            kotlinx.coroutines.runBlocking { configFlow.first() }
        } catch (e: Exception) {
            PetConfigData()
        }
    }
}
