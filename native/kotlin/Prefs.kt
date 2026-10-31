package com.openkeys.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Single JSON blob shared by the React Native app and the keyboard service. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("openkeys", Context.MODE_PRIVATE)
    var cfg: JSONObject = JSONObject()
        private set

    init { reload() }

    fun reload() {
        cfg = try { JSONObject(sp.getString("config", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    fun raw(): String = sp.getString("config", "{}") ?: "{}"

    fun save(json: String) {
        JSONObject(json) // validate
        sp.edit().putString("config", json).putLong("rev", System.nanoTime()).apply()
        reload()
    }

    /** Wakes the keyboard service (used after layout files change). */
    fun touch() { sp.edit().putLong("rev", System.nanoTime()).apply() }

    fun bool(k: String, d: Boolean) = cfg.optBoolean(k, d)
    fun int(k: String, d: Int) = cfg.optInt(k, d)
    fun str(k: String, d: String) = cfg.optString(k, d)

    fun enabledLayouts(): List<String> {
        val l = jsonStrings(cfg.optJSONArray("enabledLayouts"))
        return if (l.isEmpty()) listOf("en_qwerty", "bn_phonetic") else l
    }

    fun shortcuts(): Map<String, String> {
        val o = cfg.optJSONObject("shortcuts") ?: return emptyMap()
        val m = HashMap<String, String>()
        o.keys().forEach { m[it.lowercase()] = o.optString(it) }
        return m
    }
}
