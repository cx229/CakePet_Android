package com.cx.cakepet

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable, type-preserving DataStore Preferences interchange. Unknown
 * current keys survive ordinary imports; only validated, supported entries
 * are applied. No second DataStore may be opened on an active .preferences_pb.
 */
object PreferenceCodec {
    suspend fun snapshot(store: DataStore<Preferences>): JSONObject {
        val out = JSONObject()
        store.data.first().asMap().forEach { (key, value) ->
            val item = when (value) {
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> if (value.isFinite()) JSONObject().put("type", "float").put("value", value.toDouble()) else null
                is Double -> if (value.isFinite()) JSONObject().put("type", "double").put("value", value) else null
                is String -> JSONObject().put("type", "string").put("value", value)
                is Set<*> -> if (value.all { it is String }) JSONObject().put("type", "strings")
                    .put("value", JSONArray(value.toList())) else null
                else -> null
            }
            if (item != null) out.put(key.name, item)
        }
        return out
    }

    /** Invalid and newer/retired fields are ignored, not allowed to crash import. */
    suspend fun apply(store: DataStore<Preferences>, source: JSONObject, replace: Boolean = false): Int {
        val entries = mutableListOf<Pair<String, Any>>()
        val names = source.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name.isBlank() || name.length > 180) continue
            val value = source.optJSONObject(name) ?: continue
            val decoded: Any = try {
                when (value.getString("type")) {
                    "boolean" -> value.getBoolean("value")
                    "int" -> value.getInt("value")
                    "long" -> value.getLong("value")
                    "float" -> value.getDouble("value").toFloat().also { require(it.isFinite()) }
                    "double" -> value.getDouble("value").also { require(it.isFinite()) }
                    "string" -> value.getString("value")
                    "strings" -> {
                        val arr = value.getJSONArray("value")
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    else -> continue
                }
            } catch (_: Exception) { continue }
            entries += name to decoded
        }
        store.edit { prefs ->
            if (replace) prefs.clear()
            for ((name, value) in entries) {
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Int -> prefs[intPreferencesKey(name)] = value
                    is Long -> prefs[longPreferencesKey(name)] = value
                    is Float -> prefs[floatPreferencesKey(name)] = value
                    is Double -> prefs[doublePreferencesKey(name)] = value
                    is String -> prefs[stringPreferencesKey(name)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
                }
            }
        }
        return entries.size
    }

    fun entries(obj: JSONObject): List<String> {
        val found = mutableListOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) found += keys.next()
        return found.sorted()
    }

    fun displayValue(entry: JSONObject): String = when (entry.optString("type")) {
        "strings" -> entry.optJSONArray("value")?.toString() ?: "[]"
        else -> entry.opt("value")?.toString() ?: ""
    }

    fun editable(type: String, text: String): JSONObject {
        val obj = JSONObject().put("type", type)
        when (type) {
            "boolean" -> obj.put("value", text.toBooleanStrict())
            "int" -> obj.put("value", text.toInt())
            "long" -> obj.put("value", text.toLong())
            "float" -> obj.put("value", text.toFloat().also { require(it.isFinite()) })
            "double" -> obj.put("value", text.toDouble().also { require(it.isFinite()) })
            "string" -> obj.put("value", text)
            else -> error("此设置类型无法直接编辑")
        }
        return obj
    }
}
