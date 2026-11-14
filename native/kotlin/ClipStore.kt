package com.openkeys.keyboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [file] / [len] are only set for large texts kept in a file: [value] is then just a short preview. */
class Clip(val id: String, val type: String, val value: String, val pinned: Boolean, val ts: Long, val file: String = "", val len: Int = 0) {
    val isLarge get() = file.isNotEmpty()
    val length get() = if (isLarge) len else value.length
}

/** Clipboard history (text + images) and emoji recents / skin-tone choices. */
object ClipStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("openkeys_clip", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.applicationContext.filesDir, "clips").also { it.mkdirs() }
    fun imageFile(c: Context, name: String) = File(dir(c), name)

    private fun load(c: Context): MutableList<Clip> {
        val a = try { JSONArray(sp(c).getString("clips", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Clip>()
        for (i in 0 until a.length()) {
            try {
                val o = a.getJSONObject(i)
                val id = o.optString("id"); val type = o.optString("type"); val v = o.optString("value")
                if (id.isEmpty() || v.isEmpty() || (type != "text" && type != "image")) continue
                out.add(Clip(id, type, v, o.optBoolean("pin"), o.optLong("ts"), o.optString("file"), o.optInt("len")))
            } catch (e: Exception) { }   // skip a corrupt entry instead of crashing the keyboard
        }
        return out
    }

    private fun save(c: Context, l: List<Clip>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("value", it.value).put("pin", it.pinned).put("ts", it.ts).put("file", it.file).put("len", it.len)) }
        sp(c).edit().putString("clips", a.toString()).apply()
    }

    private fun dropFiles(c: Context, it: Clip) {
        try { if (it.type == "image") imageFile(c, it.value).delete() else if (it.isLarge) File(dir(c), it.file).delete() } catch (e: Throwable) { }
    }

    fun items(c: Context): List<Clip> {
        val l = try { load(c) } catch (e: Throwable) { ArrayList() }
        return l.filter { it.pinned } + l.filter { !it.pinned }
    }

    private fun add(c: Context, clip: Clip) {
        val l = load(c)
        val old = l.firstOrNull { it.id == clip.id }
        l.removeAll { it.id == clip.id }
        l.add(0, Clip(clip.id, clip.type, clip.value, old?.pinned ?: false, clip.ts, clip.file, clip.len))
        // keep at most 40 texts and 20 images (pinned items are never dropped)
        var texts = 0; var imgs = 0
        val keep = ArrayList<Clip>()
        for (it in l) {
            val ok = it.pinned || (if (it.type == "text") ++texts <= 40 else ++imgs <= 20)
            if (ok) keep.add(it) else dropFiles(c, it)
        }
        save(c, keep)
    }

    const val MAX_TEXT = 20_000          // inline limit (kept inside preferences)
    const val MAX_LARGE = 5_000_000      // absolute limit when "store large copies" is on
    private const val PREVIEW = 1_000

    /** Settings -> Typing -> "Store large copied text" (default on). */
    fun largeEnabled(c: Context): Boolean = try { Prefs(c).bool("clipLargeText", true) } catch (e: Throwable) { true }

    fun addText(c: Context, raw: String) {
        try {
            val large = largeEnabled(c)
            if (raw.length > (if (large) MAX_LARGE else MAX_TEXT * 5)) return
            if (raw.length <= MAX_TEXT) { addTextInternal(c, raw, raw.length); return }
            if (!large) { addTextInternal(c, raw.substring(0, MAX_TEXT), MAX_TEXT); return }   // setting off: trim
            val name = "text_" + System.currentTimeMillis() + ".txt"
            File(dir(c), name).writeText(raw)
            add(c, Clip("t:" + raw.hashCode() + ":" + raw.length, "text", raw.substring(0, PREVIEW), false, System.currentTimeMillis(), name, raw.length))
        } catch (e: Throwable) { }
    }

    private fun addTextInternal(c: Context, t: String, len: Int) =
        add(c, Clip("t:" + t.hashCode() + ":" + t.length, "text", t, false, System.currentTimeMillis()))

    /** Full text of a clip (reads the file for large clips). */
    fun fullText(c: Context, clip: Clip): String =
        if (!clip.isLarge) clip.value else try { File(dir(c), clip.file).readText() } catch (e: Throwable) { clip.value }

    /** Copies the image into private storage; returns false if it could not be read. */
    const val MAX_IMAGE = 20_000_000L

    fun addImage(c: Context, uri: Uri): Boolean {
        var f: File? = null
        return try {
            val type = c.contentResolver.getType(uri) ?: "image/png"
            val ext = when { type.contains("jpeg") || type.contains("jpg") -> "jpg"; type.contains("gif") -> "gif"; type.contains("webp") -> "webp"; else -> "png" }
            val name = "clip_" + System.currentTimeMillis() + "." + ext
            val out = imageFile(c, name); f = out
            c.contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024); var total = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        total += n; if (total > MAX_IMAGE) throw java.io.IOException("image too large")
                        o.write(buf, 0, n)
                    }
                }
            } ?: return false
            if (out.length() == 0L) { out.delete(); return false }
            // must be a decodable image, otherwise it would only break the panel
            val bo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(out.path, bo)
            if (bo.outWidth <= 0 || bo.outHeight <= 0) { out.delete(); return false }
            add(c, Clip("i:$name", "image", name, false, System.currentTimeMillis()))
            true
        } catch (e: Throwable) { try { f?.delete() } catch (x: Throwable) { }; false }
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.')) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }

    fun togglePin(c: Context, id: String) {
        save(c, load(c).map { if (it.id == id) Clip(it.id, it.type, it.value, !it.pinned, it.ts, it.file, it.len) else it })
    }

    fun remove(c: Context, id: String) {
        val l = load(c)
        l.firstOrNull { it.id == id }?.let { dropFiles(c, it) }
        save(c, l.filter { it.id != id })
    }

    fun clearUnpinned(c: Context) {
        val l = load(c)
        l.filter { !it.pinned }.forEach { dropFiles(c, it) }
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
