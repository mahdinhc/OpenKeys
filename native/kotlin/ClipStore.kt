package com.openkeys.keyboard

import android.content.Context
import org.json.JSONArray

object ClipStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("openkeys_clip", Context.MODE_PRIVATE)
    private fun list(c: Context, k: String): MutableList<String> {
        val a = try { JSONArray(sp(c).getString(k, "[]")) } catch (e: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }
    private fun put(c: Context, k: String, l: List<String>) { sp(c).edit().putString(k, JSONArray(l).toString()).apply() }

    fun items(c: Context): List<Pair<String, Boolean>> {
        val pins = list(c, "pins")
        return pins.map { it to true } + list(c, "items").filter { it !in pins }.map { it to false }
    }
    fun add(c: Context, t: String) {
        val l = list(c, "items"); l.remove(t); l.add(0, t)
        while (l.size > 40) l.removeAt(l.size - 1)
        put(c, "items", l)
    }
    fun toggle(c: Context, t: String) { val p = list(c, "pins"); if (!p.remove(t)) p.add(0, t); put(c, "pins", p) }
    fun clear(c: Context) = put(c, "items", emptyList())

    fun recentEmoji(c: Context): List<String> = list(c, "emoji")
    fun addEmoji(c: Context, e: String) {
        val l = list(c, "emoji"); l.remove(e); l.add(0, e)
        while (l.size > 32) l.removeAt(l.size - 1)
        put(c, "emoji", l)
    }
}
