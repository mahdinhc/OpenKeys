package com.openkeys.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class KeyDef(
    val label: String, val output: String, val hints: List<String>,
    val weight: Float, val type: String, val param: String? = null, val span: Int = 1
)

/**
 * Layout DSL (one string per row, tokens separated by spaces):
 *   a              key "a"
 *   a|à|á          key "a" with long-press alternatives
 *   ক=\u0995\u09CD  label "ক", outputs any Unicode sequence
 *   \u{1F600}      Unicode escapes work everywhere
 *   {shift} {del} {enter} {space:5} {sym} {sym2} {abc} {lang} {emoji} {mic}
 *   {layer=name:1.4}  jump to a custom layer, {pad:0.5} spacer, {pipe} {eq} literal | and =
 *   {mod=ctrl~Ctrl} sticky modifier (ctrl|alt|meta; tap = next key, double tap = lock)
 *   {key=ctrl+shift+z~Redo} / {key=DPAD_UP~Up} key event, optionally with modifiers
 *   {act=copy~Copy} copy | cut | paste | selectall
 *   ":w" after a special sets its width weight
 */
object LayoutParser {
    private val esc = Regex("\\\\u\\{([0-9a-fA-F]{1,6})\\}|\\\\u([0-9a-fA-F]{4})")

    fun unescape(s: String): String = esc.replace(s) { m ->
        val hex = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
        String(Character.toChars(hex.toInt(16)))
    }

    fun row(s: String): List<KeyDef> =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { token(it) }

    fun token(raw: String): KeyDef {
        val t = unescape(raw)
        if (t.length > 2 && t.startsWith("{") && t.endsWith("}")) {
            val parts = t.substring(1, t.length - 1).split(":")
            val head = parts[0]
            val w = parts.getOrNull(1)?.toFloatOrNull()
            val span = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val name = head.substringBefore("=")
            val param = if (head.contains("=")) head.substringAfter("=") else null
            val none = emptyList<String>()
            return when (name) {
                "shift" -> KeyDef("⇧", "", none, w ?: 1.5f, "shift")
                "del" -> KeyDef("⌫", "", none, w ?: 1.5f, "del")
                "enter" -> KeyDef("⏎", "", none, w ?: 1.5f, "enter")
                "space" -> KeyDef("", " ", none, w ?: 5f, "space")
                "sym" -> KeyDef("?123", "", none, w ?: 1.4f, "layer", "symbols")
                "sym2" -> KeyDef("=\\<", "", none, w ?: 1.4f, "layer", "symbols2")
                "abc" -> KeyDef("ABC", "", none, w ?: 1.4f, "layer", "base")
                "layer" -> {
                    val lp = param ?: "base"
                    val layer = lp.substringBefore("~")
                    KeyDef(if (lp.contains("~")) lp.substringAfter("~") else layer, "", none, w ?: 1.4f, "layer", layer)
                }
                "mod", "key", "act" -> {
                    val spec = param ?: ""
                    return KeyDef(if (spec.contains("~")) spec.substringAfter("~") else spec, "", none, w ?: 1f, name, spec.substringBefore("~"))
                }
                "stack" -> KeyDef("", "", (param ?: "").split(";").filter { it.isNotEmpty() }, w ?: 1f, "stack", null, span)
                "lang" -> KeyDef("🌐", "", none, w ?: 1f, "lang")
                "emoji" -> KeyDef("☺", "", none, w ?: 1f, "emoji")
                "mic" -> KeyDef("🎤", "", none, w ?: 1f, "mic")
                "pad" -> KeyDef("", "", none, w ?: 0.5f, "pad")
                "pipe" -> KeyDef("|", "|", none, w ?: 1f, "char")
                "eq" -> KeyDef("=", "=", none, w ?: 1f, "char")
                else -> KeyDef(t, t, none, 1f, "char")
            }
        }
        var tt = t
        var cw = 1f
        Regex("@(\\d+(?:\\.\\d+)?)$").find(tt)?.let { cw = it.groupValues[1].toFloat(); tt = tt.substring(0, it.range.first) }
        val segs = tt.split("|")
        val head = segs[0]
        val hints = segs.drop(1).filter { it.isNotEmpty() }
        val (label, out) =
            if (head.length > 1 && head.contains("=") && !head.startsWith("="))
                head.substringBefore("=") to head.substringAfter("=")
            else head to head
        return KeyDef(label, out, hints, cw, "char")
    }
}

val NUMPAD_ROWS = listOf(
    "{stack=+;-;*;/:0.62:3} 1 2 3 %@0.62",
    "{pad:0.62} 4 5 6 {space:0.62}",
    "{pad:0.62} 7 8 9 {del:0.62}",
    "{layer=letters~ABC:0.62} ,|;|:@0.34 {layer=symbols~!?#:0.66} 0 =@0.58 .@0.4 {enter:0.62}"
)

class LayoutDef(val raw: JSONObject) {
    val id: String = raw.getString("id")
    val name: String = raw.optString("name", id)
    val locale: String = raw.optString("locale", "en")
    val mode: String = raw.optString("mode", "direct")          // "direct" | "translit"
    val dict: String = raw.optString("dict", locale.substringBefore('-'))
    val autoCap: Boolean = raw.optBoolean("autoCap", false)
    val swipe: Boolean = raw.optBoolean("swipe", false)
    val noNumberRow: Boolean = raw.optBoolean("noNumberRow", false)
    val rowScale: Float = raw.optDouble("rowScale", 1.0).toFloat()
    val sentenceEnd: String = raw.optString("sentenceEnd", ".")
    val layers: Map<String, List<List<KeyDef>>>
    val numberRow: List<KeyDef>?
    val translit: Transliterator?

    init {
        val lo = raw.getJSONObject("layers")
        val m = LinkedHashMap<String, List<List<KeyDef>>>()
        lo.keys().forEach { n ->
            val a = lo.getJSONArray(n)
            m[n] = (0 until a.length()).map { LayoutParser.row(a.getString(it)) }
        }
        require(m["base"]?.isNotEmpty() == true) { "layers.base is required" }
        if (!m.containsKey("numpad")) m["numpad"] = NUMPAD_ROWS.map { LayoutParser.row(it) }
        layers = m
        numberRow = raw.optString("numberRow", "").takeIf { it.isNotBlank() }?.let { LayoutParser.row(it) }
        translit = if (mode == "translit") Transliterator(raw.getJSONObject("translit")) else null
    }
}

class LayoutRepo(private val ctx: Context) {
    companion object { val BUILTIN = listOf("en_qwerty", "en_desktop", "bn_phonetic", "bn_bijoy", "numeric") }

    private val cache = HashMap<String, LayoutDef>()
    private val dir: File get() = File(ctx.filesDir, "layouts").also { it.mkdirs() }

    fun invalidate() = cache.clear()

    fun builtinJson(id: String): JSONObject? = try {
        JSONObject(ctx.assets.open("layouts/$id.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) { DevLog.e(ctx, "Layout", "built-in layout '$id' could not be read", e); null }

    fun customJson(id: String): JSONObject? = try {
        val f = File(dir, "$id.json"); if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) { DevLog.e(ctx, "Layout", "custom layout '$id' is not valid JSON", e); null }

    /** User files override built-ins with the same id. */
    fun get(id: String): LayoutDef? {
        cache[id]?.let { return it }
        val j = customJson(id) ?: builtinJson(id) ?: return null
        return try { LayoutDef(j).also { cache[id] = it } } catch (e: Exception) { DevLog.e(ctx, "Layout", "layout '$id' failed to parse", e); null }
    }

    fun save(json: JSONObject) {
        LayoutDef(json) // validates
        val id = json.getString("id")
        require(Regex("[a-z0-9_]{2,40}").matches(id)) { "id must be 2-40 chars of a-z, 0-9, _" }
        File(dir, "$id.json").writeText(json.toString())
        cache.remove(id)
    }

    fun delete(id: String) { File(dir, "$id.json").delete(); cache.remove(id) }

    fun customAll(): JSONArray {
        val a = JSONArray()
        dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach {
            try { a.put(JSONObject(it.readText())) } catch (e: Exception) { }
        }
        return a
    }

    /** [{id,name,builtin,overridden,layout}] for the settings app. */
    fun info(): JSONArray {
        val out = JSONArray()
        val custom = HashMap<String, JSONObject>()
        val ca = customAll()
        for (i in 0 until ca.length()) custom[ca.getJSONObject(i).optString("id")] = ca.getJSONObject(i)
        for (id in BUILTIN) {
            val j = custom[id] ?: builtinJson(id) ?: continue
            out.put(JSONObject().put("id", id).put("name", j.optString("name", id)).put("builtin", true)
                .put("overridden", custom.containsKey(id)).put("layout", j))
        }
        for ((id, j) in custom) if (id !in BUILTIN)
            out.put(JSONObject().put("id", id).put("name", j.optString("name", id)).put("builtin", false)
                .put("overridden", false).put("layout", j))
        return out
    }
}
