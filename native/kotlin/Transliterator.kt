package com.openkeys.keyboard

import org.json.JSONObject

/**
 * Generic roman -> Unicode converter for Indic-style scripts. Rules (JSON):
 *  consonants: {"k":"ক"}            vowels: {"a":["আ","া"]}  [independent, dependent sign]
 *  marks: {"ng":"ং"}                 joiner: "`"  (consonant + joiner => virama, builds conjuncts)
 *  virama: "্"                        autoDouble: true  ("kk" => ক্ক)
 * Longest match wins. Unknown upper-case letters fall back to their lower-case rule.
 */
class Transliterator(rules: JSONObject) {
    private class E(val kind: Int, val a: String, val b: String)

    private val map = HashMap<String, E>()
    private val virama = rules.optString("virama", "\u09CD")
    private val joiner = rules.optString("joiner", "`")
    private var maxLen = 1
    private val first = HashSet<Char>()

    init {
        fun put(k: String, e: E) { map[k] = e; if (k.length > maxLen) maxLen = k.length; first.add(k[0]) }
        rules.optJSONObject("consonants")?.let { o -> o.keys().forEach { k -> put(k, E(0, o.getString(k), "")) } }
        rules.optJSONObject("vowels")?.let { o ->
            o.keys().forEach { k -> val a = o.getJSONArray(k); put(k, E(1, a.getString(0), a.getString(1))) }
        }
        rules.optJSONObject("marks")?.let { o -> o.keys().forEach { k -> put(k, E(2, o.getString(k), "")) } }
        if (rules.optBoolean("autoDouble", true)) {
            map.filter { it.value.kind == 0 && it.key.length == 1 }.forEach { (k, e) ->
                if (!map.containsKey(k + k)) put(k + k, E(0, e.a + virama + e.a, ""))
            }
        }
        if (joiner.isNotEmpty()) first.add(joiner[0])
    }

    fun accepts(ch: Char) = ch in first || ch.lowercaseChar() in first

    private fun lookup(s: String, i: Int): Pair<E, Int>? {
        var l = minOf(maxLen, s.length - i)
        while (l > 0) {
            map[s.substring(i, i + l)]?.let { return it to l }
            l--
        }
        map[s[i].lowercaseChar().toString()]?.let { return it to 1 }
        return null
    }

    fun convert(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var prevCons = false
        while (i < src.length) {
            val found = lookup(src, i)
            if (found == null) {
                val ch = src[i]
                if (joiner.isNotEmpty() && ch == joiner[0]) { if (prevCons) sb.append(virama) }
                else { sb.append(ch); prevCons = false }
                i++
                continue
            }
            val (e, len) = found
            when (e.kind) {
                0 -> { sb.append(e.a); prevCons = true }
                1 -> { sb.append(if (prevCons) e.b else e.a); prevCons = false }
                else -> { sb.append(e.a); prevCons = false }
            }
            i += len
        }
        return sb.toString()
    }
}
