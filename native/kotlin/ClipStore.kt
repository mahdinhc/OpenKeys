package com.openkeys.keyboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Clip(val id: String, val type: String, val value: String, val pinned: Boolean, val ts: Long)

/** Clipboard history (text + images) and emoji recents / skin-tone choices. */
object ClipStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("openkeys_clip", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.applicationContext.filesDir, "clips").also { it.mkdirs() }
    fun imageFile(c: Context, name: String) = File(dir(c), name)

    private fun load(c: Context): MutableList<Clip> {
        val a = try { JSONArray(sp(c).getString("clips", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Clip>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Clip(o.getString("id"), o.getString("type"), o.getString("value"), o.optBoolean("pin"), o.optLong("ts")))
        }
        return out
    }

    private fun save(c: Context, l: List<Clip>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("value", it.value).put("pin", it.pinned).put("ts", it.ts)) }
        sp(c).edit().putString("clips", a.toString()).apply()
    }

    fun items(c: Context): List<Clip> {
        val l = load(c)
        return l.filter { it.pinned } + l.filter { !it.pinned }
    }

    private fun add(c: Context, clip: Clip) {
        val l = load(c)
        val old = l.firstOrNull { it.id == clip.id }
        l.removeAll { it.id == clip.id }
        l.add(0, Clip(clip.id, clip.type, clip.value, old?.pinned ?: false, clip.ts))
        // keep at most 40 texts and 20 images (pinned items are never dropped)
        var texts = 0; var imgs = 0
        val keep = ArrayList<Clip>()
        for (it in l) {
            val ok = it.pinned || (if (it.type == "text") ++texts <= 40 else ++imgs <= 20)
            if (ok) keep.add(it) else if (it.type == "image") imageFile(c, it.value).delete()
        }
        save(c, keep)
    }

    fun addText(c: Context, t: String) = add(c, Clip("t:" + t.hashCode() + ":" + t.length, "text", t, false, System.currentTimeMillis()))

    /** Copies the image into private storage; returns false if it could not be read. */
    fun addImage(c: Context, uri: Uri): Boolean {
        return try {
            val type = c.contentResolver.getType(uri) ?: "image/png"
            val ext = when { type.contains("jpeg") || type.contains("jpg") -> "jpg"; type.contains("gif") -> "gif"; type.contains("webp") -> "webp"; else -> "png" }
            val name = "clip_" + System.currentTimeMillis() + "." + ext
            val f = imageFile(c, name)
            c.contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } } ?: return false
            if (f.length() == 0L) { f.delete(); return false }
            add(c, Clip("i:$name", "image", name, false, System.currentTimeMillis()))
            true
        } catch (e: Exception) { false }
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.')) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }

    fun togglePin(c: Context, id: String) {
        save(c, load(c).map { if (it.id == id) Clip(it.id, it.type, it.value, !it.pinned, it.ts) else it })
    }

    fun remove(c: Context, id: String) {
        val l = load(c)
        l.firstOrNull { it.id == id && it.type == "image" }?.let { imageFile(c, it.value).delete() }
        save(c, l.filter { it.id != id })
    }

    fun clearUnpinned(c: Context) {
        val l = load(c)
        l.filter { !it.pinned && it.type == "image" }.forEach { imageFile(c, it.value).delete() }
        save(c, l.filter { it.pinned })
    }

    // ---- emoji recents and skin tones
    fun recentEmoji(c: Context): List<String> = jsonStrings(try { JSONArray(sp(c).getString("emoji", "[]")) } catch (e: Exception) { null })
    fun addEmoji(c: Context, e: String) {
        val l = recentEmoji(c).toMutableList(); l.remove(e); l.add(0, e)
        while (l.size > 32) l.removeAt(l.size - 1)
        sp(c).edit().putString("emoji", JSONArray(l).toString()).apply()
    }
    fun tone(c: Context, base: String): String? = try { JSONObject(sp(c).getString("tones", "{}")).optString(base).ifEmpty { null } } catch (e: Exception) { null }
    fun setTone(c: Context, base: String, variant: String) {
        val o = try { JSONObject(sp(c).getString("tones", "{}")) } catch (e: Exception) { JSONObject() }
        if (variant == base) o.remove(base) else o.put(base, variant)
        sp(c).edit().putString("tones", o.toString()).apply()
    }

    // ---- Emoji Kitchen: recently sent mixes and favourite mixes, stored as "a|b" pairs
    private fun pairs(c: Context, k: String): List<Pair<String, String>> =
        jsonStrings(try { JSONArray(sp(c).getString(k, "[]")) } catch (e: Exception) { null })
            .mapNotNull { s -> val i = s.indexOf('|'); if (i > 0) s.substring(0, i) to s.substring(i + 1) else null }

    private fun putPairs(c: Context, k: String, l: List<Pair<String, String>>) =
        sp(c).edit().putString(k, JSONArray(l.map { it.first + "|" + it.second }).toString()).apply()

    fun recentMixes(c: Context) = pairs(c, "mixRecent")
    fun favMixes(c: Context) = pairs(c, "mixFav")
    fun favMixKeys(c: Context): Set<String> = favMixes(c).map { EmojiKitchen.pairKey(it.first, it.second) }.toSet()

    fun addMix(c: Context, a: String, b: String) {
        val key = EmojiKitchen.pairKey(a, b)
        val l = recentMixes(c).filter { EmojiKitchen.pairKey(it.first, it.second) != key }.toMutableList()
        l.add(0, a to b)
        while (l.size > 60) l.removeAt(l.size - 1)
        putPairs(c, "mixRecent", l)
    }

    /** Adds or removes the mix from the favourites; returns true when it is now a favourite. */
    fun toggleFavMix(c: Context, a: String, b: String): Boolean {
        val key = EmojiKitchen.pairKey(a, b)
        val l = favMixes(c)
        val had = l.any { EmojiKitchen.pairKey(it.first, it.second) == key }
        putPairs(c, "mixFav", if (had) l.filter { EmojiKitchen.pairKey(it.first, it.second) != key } else listOf(a to b) + l)
        return !had
    }
}
