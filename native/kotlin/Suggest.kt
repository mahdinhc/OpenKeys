package com.openkeys.keyboard

import android.content.Context
import java.io.File
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ln

class SuggestEngine(ctx: Context) {
    private val app = ctx.applicationContext
    private val dicts = HashMap<String, WordDict>()
    fun dict(code: String): WordDict = dicts.getOrPut(code) { WordDict(app, code) }
    fun flush() = dicts.values.forEach { it.flush() }
    fun clearUser() {
        File(app.filesDir.path).listFiles { f -> f.name.startsWith("user_") }?.forEach { it.delete() }
        dicts.values.forEach { it.clearUser() }
    }
}

/** Frequency dictionary (assets/dict/<code>.txt: "word" or "word<TAB>freq", most common first). */
class WordDict(ctx: Context, private val code: String) {
    private val words = TreeMap<String, Int>()
    private val user = TreeMap<String, Int>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val userFile = File(ctx.filesDir, "user_$code.txt")
    private var dirty = 0

    init {
        try {
            val lines = ctx.assets.open("dict/$code.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            lines.forEachIndexed { i, ln ->
                val p = ln.split("\t")
                words[p[0].trim().lowercase()] = p.getOrNull(1)?.trim()?.toIntOrNull() ?: (lines.size - i)
            }
        } catch (e: Exception) { }
        try {
            if (userFile.exists()) userFile.readLines().forEach {
                val p = it.split("\t"); if (p.size >= 2) user[p[0]] = p[1].toIntOrNull() ?: 1
            }
        } catch (e: Exception) { }
    }

    private fun known(w: String) = words.containsKey(w) || (user[w] ?: 0) >= 2

    fun complete(prefix: String, n: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val hits = HashMap<String, Int>()
        for ((w, f) in words.subMap(p, p + '\uffff')) hits[w] = f
        for ((w, f) in user.subMap(p, p + '\uffff')) hits[w] = (hits[w] ?: 0) + f * 50 + 500
        return hits.entries.sortedByDescending { it.value }.map { it.key }
            .filter { it != p }.take(n).map { matchCase(prefix, it) }
    }

    fun correct(word: String): String? {
        val w = word.lowercase()
        if (w.length < 3 || known(w) || w.any { it.isDigit() }) return null
        val lo = w[0].toString(); val hi = lo + '\uffff'
        var best: String? = null; var bf = -1
        for (m in arrayOf(words, user)) for ((c, f) in m.subMap(lo, hi)) {
            if (abs(c.length - w.length) > 1) continue
            if (f > bf && within1(w, c)) { best = c; bf = f }
        }
        return best
    }

    fun next(prev: String, n: Int): List<String> =
        bigram[prev.lowercase()]?.entries?.sortedByDescending { it.value }?.map { it.key }?.take(n) ?: emptyList()

    fun learn(word: String, prev: String?) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { it.isDigit() }) return
        user[w] = (user[w] ?: 0) + 1
        if (!prev.isNullOrEmpty()) bigram.getOrPut(prev.lowercase()) { HashMap() }.merge(w, 1) { a, b -> a + b }
        if (++dirty >= 10) flush()
    }

    /** Gesture typing: path = distinct letters under the finger, in order. */
    fun swipe(path: String, n: Int): List<String> {
        if (path.length < 2) return emptyList()
        val f0 = path.first(); val last = path.last()
        val res = ArrayList<Pair<String, Double>>()
        for (m in arrayOf(words, user)) for ((w, f) in m.subMap(f0.toString(), f0.toString() + '\uffff')) {
            if (w.length < 2 || w.last() != last || !w.all { it.isLetter() }) continue
            val cw = StringBuilder().also { sb -> w.forEach { if (sb.isEmpty() || sb.last() != it) sb.append(it) } }.toString()
            if (!subseq(cw, path)) continue
            res.add(w to cw.length.toDouble() / path.length * 3 + ln(f.toDouble() + 2) / 10)
        }
        return res.sortedByDescending { it.second }.map { it.first }.distinct().take(n)
    }

    fun flush() {
        dirty = 0
        try { userFile.writeText(user.entries.joinToString("\n") { "${it.key}\t${it.value}" }) } catch (e: Exception) { }
    }

    fun clearUser() { user.clear(); bigram.clear(); userFile.delete() }

    private fun subseq(a: String, b: String): Boolean {
        var i = 0
        for (c in b) if (i < a.length && a[i] == c) i++
        return i == a.length
    }

    private fun within1(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length; val lb = b.length
        if (abs(la - lb) > 1) return false
        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return true
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
        }
        val (s, l) = if (la < lb) a to b else b to a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }
}
