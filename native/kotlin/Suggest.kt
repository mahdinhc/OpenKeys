package com.openkeys.keyboard

import android.content.Context
import java.io.File
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

class SuggestEngine(ctx: Context) {
    private val app = ctx.applicationContext
    private val dicts = HashMap<String, WordDict>()
    /** Asked on every lookup: may Urban Dictionary words be used? (Settings -> Typing -> Urban Dictionary words) */
    @Volatile var urbanOn: () -> Boolean = { false }
    /** Asked on every lookup: may anime / manga words be used? (Settings -> Typing -> Anime & manga words) */
    @Volatile var animeOn: () -> Boolean = { false }
    @Synchronized fun dict(code: String): WordDict = dicts.getOrPut(code) { WordDict(app, code, { urbanOn() }, { animeOn() }) }
    @Synchronized fun flush() = dicts.values.forEach { it.flush() }
    @Synchronized fun clearUser() {
        File(app.filesDir.path).listFiles { f -> f.name.startsWith("user_") }?.forEach { it.delete() }
        dicts.values.forEach { it.clearUser() }
    }
}

/** Input for gesture typing: raw finger path + the centre of every letter key, all in view pixels. */
class SwipeInput(val pts: FloatArray, val centers: Map<Char, FloatArray>, val keyW: Float)

/**
 * Frequency dictionary (assets/dict/<code>.txt: "word" or "word<TAB>freq") + learned words/bigrams.
 * Autocorrect = keyboard-aware weighted edit distance x frequency x context; swipe = path-shape matching
 * against the ideal path through the key centres of every candidate word (location channel of SHARK2).
 */
class WordDict(ctx: Context, private val code: String, private val urbanOn: () -> Boolean = { false }, private val animeOn: () -> Boolean = { false }) {
    private var bw: Array<String> = emptyArray()          // base words, sorted
    private var bf = IntArray(0)                          // their frequencies
    private var byLen: Array<IntArray> = emptyArray()     // indices into bw, bucketed by length
    private val top = ArrayList<String>()                 // most common words (fallback predictions)
    private val fuzzy = code != "en"                      // phonetic scripts: match on a sound-alike skeleton
    private var sk: Array<String> = emptyArray()          // skeletons, sorted
    private var skIdx = IntArray(0)                       // sk[i] belongs to word bw[skIdx[i]]
    private val auto = HashMap<String, String>()          // roman -> Bangla autocorrect (Avro autodict)
    private var suffixes: List<String> = emptyList()      // Bangla suffixes (Avro suffix table)
    private val user = TreeMap<String, Int>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val userFile = File(ctx.filesDir, "user_$code.txt")
    private val biFile = File(ctx.filesDir, "user_${code}_bi.txt")
    private var dirty = 0
    // Large English lists are read through memory-mapped binary files (see BinDict); other languages use the text path.
    private val mainBin: BinDict? = if (fuzzy) null else BinDict.open(ctx, "dict/$code.bin")
    private val urbanBin: BinDict? = if (mainBin == null) null else BinDict.open(ctx, "dict/${code}_urban.bin")
    private val animeBin: BinDict? = if (mainBin == null) null else BinDict.open(ctx, "dict/${code}_anime.bin")
    /** Optional word lists the user switched on (each holds only words that are not in the main list). */
    private fun extras(): List<BinDict> {
        val l = ArrayList<BinDict>(2)
        urbanBin?.let { if (urbanOn()) l.add(it) }
        animeBin?.let { if (animeOn()) l.add(it) }
        return l
    }
    private val nextTab = HashMap<String, List<String>>()      // curated next-word table (assets/dict/<code>_next.txt)

    init {
        if (mainBin != null) {
            for (i in mainBin.fallback) { if (top.size >= 40) break; top.add(mainBin.word(i)) }
            try {
                ctx.assets.open("dict/${code}_next.txt").bufferedReader().forEachLine { ln ->
                    val p = ln.split("\t"); if (p.size >= 2 && !ln.startsWith("#")) nextTab[p[0]] = p[1].trim().split(" ").filter { it.isNotEmpty() }
                }
            } catch (e: Exception) { }
        }
        if (mainBin == null) try {
            val lines = ctx.assets.open("dict/$code.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            val n = lines.size
            val ws = arrayOfNulls<String>(n); val fs = IntArray(n)
            lines.forEachIndexed { i, ln ->
                val p = ln.split("\t")
                ws[i] = p[0].trim().lowercase()
                fs[i] = p.getOrNull(1)?.trim()?.toIntOrNull() ?: (n - i)
                if (top.size < 40) top.add(ws[i]!!)
            }
            val order = (0 until n).sortedBy { ws[it] }
            bw = Array(n) { ws[order[it]]!! }; bf = IntArray(n) { fs[order[it]] }
            val cnt = IntArray(40)
            for (w in bw) cnt[min(w.length, 39)]++
            val lists = Array(40) { IntArray(cnt[it]) }; val pos = IntArray(40)
            bw.forEachIndexed { i, w -> val l = min(w.length, 39); lists[l][pos[l]++] = i }
            byLen = lists
            if (fuzzy) {
                val skw = Array(n) { skeleton(bw[it]) }
                val so = (0 until n).sortedBy { skw[it] }
                sk = Array(n) { skw[so[it]] }; skIdx = IntArray(n) { so[it] }
            }
        } catch (e: Exception) { }
        try {
            ctx.assets.open("dict/${code}_auto.txt").bufferedReader().forEachLine { ln ->
                val p = ln.split("\t"); if (p.size >= 2) auto[p[0]] = p[1]
            }
        } catch (e: Exception) { }
        try { suffixes = ctx.assets.open("dict/${code}_suffix.txt").bufferedReader().readLines().filter { it.isNotBlank() }.sortedByDescending { it.length } } catch (e: Exception) { }
        try {
            if (userFile.exists()) userFile.readLines().forEach {
                val p = it.split("\t"); if (p.size >= 2) user[p[0]] = p[1].toIntOrNull() ?: 1
            }
            if (biFile.exists()) biFile.readLines().forEach {
                val p = it.split("\t"); if (p.size >= 3) bigram.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 1
            }
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- lookup helpers
    private fun lower(p: String): Int {
        var lo = 0; var hi = bw.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (bw[m] < p) lo = m + 1 else hi = m }
        return lo
    }
    private fun baseIndex(w: String): Int { val i = lower(w); return if (i < bw.size && bw[i] == w) i else -1 }
    private fun freqOf(w: String): Int { val i = baseIndex(w); return (if (i >= 0) bf[i] else 0) + (user[w] ?: 0) * 50 }
    fun known(w: String): Boolean {
        val m = mainBin
        if (m != null) return m.indexOf(w) >= 0 || (user[w] ?: 0) >= 2 || urbanIdx(w) >= 0
        return baseIndex(w) >= 0 || (user[w] ?: 0) >= 2
    }
    private fun urbanIdx(w: String): Int { for (u in extras()) { val i = u.indexOf(w); if (i >= 0) return i }; return -1 }

    // ---------------------------------------------------------------- completion
    fun complete(prefix: String, n: Int, prev: String? = null): List<String> {
        if (prefix.isEmpty()) return emptyList()
        if (fuzzy) return completeFuzzy(prefix, n, prev)
        mainBin?.let { return completeBin(it, prefix, n, prev) }
        val p = prefix.lowercase()
        val hits = HashMap<String, Int>()
        var i = lower(p)
        while (i < bw.size && bw[i].startsWith(p)) { hits[bw[i]] = bf[i]; i++ }
        for ((w, f) in user.subMap(p, p + '\uffff')) hits[w] = (hits[w] ?: 0) + f * 50 + 500
        val bi = prev?.let { bigram[it.lowercase()] }
        return hits.entries.sortedByDescending { it.value + (bi?.get(it.key)?.let { c -> 3000 * c } ?: 0) }
            .map { it.key }.filter { it != p }.take(n).map { matchCase(prefix, it) }
    }

    // ---------------------------------------------------------------- phonetic scripts (Bangla): sound-alike matching
    /** Collapses letters that Avro-style phonetic typing cannot tell apart (ই/ঈ, ন/ণ, শ/ষ/স, ত/ট ...). */
    private fun skeleton(w: String): String {
        val sb = StringBuilder(w.length)
        for (c in w) { val m = SKEL[c]; if (m == null) sb.append(c) else if (m != '\u0000') sb.append(m) }
        return sb.toString()
    }

    private fun skLower(p: String): Int {
        var lo = 0; var hi = sk.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sk[m] < p) lo = m + 1 else hi = m }
        return lo
    }

    /** Avro's roman -> Bangla autocorrect (e.g. "academy" -> অ্যাকাডেমি); null when the word isn't in it. */
    fun autoCorrect(roman: String): String? = auto[roman.lowercase()]

    private fun completeFuzzy(prefix: String, n: Int, prev: String?): List<String> {
        val p = prefix
        val sp = skeleton(p)
        if (sp.isEmpty()) return emptyList()
        val bi = prev?.let { bigram[it.lowercase()] }
        val hits = HashMap<String, Long>()
        var i = skLower(sp)
        while (i < sk.size && sk[i].startsWith(sp)) {
            val w = bw[skIdx[i]]
            hits[w] = bf[skIdx[i]].toLong() * 10 - w.length * 400L + (if (w.startsWith(p)) 3000L else 0L)
            i++
        }
        for ((w, f) in user.subMap(p, p + '\uffff')) hits[w] = (hits[w] ?: 0L) + f * 500L + 60000
        // word + suffix ("বাংলাদেশকে"): when the stem is a known word, offer stem+suffix
        if (hits.size < n && p.length >= 3) for (s in suffixes) {
            if (s.length >= p.length - 1 || !p.endsWith(s)) continue
            val ss = skeleton(p.dropLast(s.length))
            var j = skLower(ss); var best: String? = null; var bs = -1
            while (j < sk.size && sk[j] == ss) { if (bf[skIdx[j]] > bs) { bs = bf[skIdx[j]]; best = bw[skIdx[j]] }; j++ }
            if (best != null) hits.putIfAbsent(best + s, bs.toLong() * 10 - (best.length + s.length) * 400L)
            if (hits.size >= n) break
        }
        return hits.entries.sortedByDescending { it.value + (bi?.get(it.key)?.let { c -> 20000L * c } ?: 0L) }
            .map { it.key }.filter { it != p }.take(n)
    }

    // ---------------------------------------------------------------- correction
    private class Cand(val w: String, val score: Double, val cost: Double, val f: Int = 0)

    private fun special(w: String): String? = when (w) {
        "i" -> "I"; "im" -> "I'm"; "ive" -> "I've"; "dont" -> "don't"; "cant" -> "can't"; "wont" -> "won't"
        "isnt" -> "isn't"; "arent" -> "aren't"; "wasnt" -> "wasn't"; "werent" -> "weren't"; "doesnt" -> "doesn't"
        "didnt" -> "didn't"; "couldnt" -> "couldn't"; "shouldnt" -> "shouldn't"; "wouldnt" -> "wouldn't"
        "hasnt" -> "hasn't"; "havent" -> "haven't"; "hadnt" -> "hadn't"; "youre" -> "you're"; "theyre" -> "they're"
        "thats" -> "that's"; "whats" -> "what's"; "theres" -> "there's"; "youve" -> "you've"; "youll" -> "you'll"
        "theyve" -> "they've"; "weve" -> "we've"; "ur" -> null; else -> null
    }

    private fun cands(w: String, prev: String?): List<Cand> {
        val out = ArrayList<Cand>()
        val bi = prev?.let { bigram[it.lowercase()] }
        fun consider(c: String, f: Int) {
            val cost = wedit(w, c, 2.0)
            if (cost > 2.0) return
            val s = ln(f.toDouble() + 2) - 3.0 * cost + (if (bi?.containsKey(c) == true) 2.0 else 0.0)
            out.add(Cand(c, s, cost))
        }
        for (L in maxOf(2, w.length - 2)..min(w.length + 2, 39)) {
            if (L >= byLen.size) break
            for (i in byLen[L]) {
                val c = bw[i]
                if (!firstOk(w[0], c[0])) continue
                consider(c, bf[i] + (user[c] ?: 0) * 50)
            }
        }
        for ((c, f) in user) if (f >= 2 && abs(c.length - w.length) <= 2 && baseIndex(c) < 0 && firstOk(w[0], c[0])) consider(c, f * 50 + 500)
        return out.sortedByDescending { it.score }
    }

    /** Best automatic replacement for a typed word, or null when it should be left alone. */
    fun correct(word: String, prev: String? = null): String? {
        val w = word.lowercase()
        special(w)?.let { return if (it == word) null else it }
        mainBin?.let { return correctBin(it, word, w, prev) }
        if (w.length < 3 || known(w) || w.any { it.isDigit() || !(it.isLetter() || it == '\'') }) return null
        if (word.drop(1).any { it.isUpperCase() } && word.any { it.isLowerCase() }) return null   // CamelCase / names
        val best = cands(w, prev).firstOrNull() ?: return null
        val limit = if (w.length == 3) 1.0 else 1.4
        return if (best.cost <= limit) best.w else null
    }

    /** Candidate fixes for the suggestion bar (more permissive than autocorrect). */
    fun fixes(word: String, n: Int, prev: String? = null): List<String> {
        val w = word.lowercase()
        if (w.length < 2 || w.any { !(it.isLetter() || it == '\'') }) return emptyList()
        special(w)?.let { return listOf(matchCase(word, it)) }
        if (w.length < 3) return emptyList()
        mainBin?.let { return fixesBin(it, word, w, n, prev) }
        return cands(w, prev).filter { it.cost <= 1.4 && it.w != w }.take(n).map { matchCase(word, it.w) }
    }

    // ---------------------------------------------------------------- binary dictionary (English)
    /** Popularity on a log scale. The hand-ranked top words (freq >= 1,000,000, 100 per rank) keep their rank order. */
    private fun pop(f: Int): Double =
        if (f >= 1_000_000) 14.5 - 0.9 * ln(1.0 + maxOf(0.0, (2_000_000 - f) / 100.0)) else ln(f.toDouble() + 2)

    private fun isSwap(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        return i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
    }

    private fun completeBin(m: BinDict, prefix: String, n: Int, prev: String?): List<String> {
        val p = prefix.lowercase()
        val pb = p.toByteArray(Charsets.UTF_8)
        val hits = HashMap<String, Int>()
        for (i in m.top(pb, n * 6 + 8)) hits[m.word(i)] = m.freq(i)
        for ((w, f) in user.subMap(p, p + '\uffff')) hits[w] = (hits[w] ?: 0) + f * 50 + 500
        val bi = prev?.let { bigram[it.lowercase()] }
        if (bi != null) for (w in bi.keys) if (w.startsWith(p) && !hits.containsKey(w)) { val i = m.indexOf(w); hits[w] = if (i >= 0) m.freq(i) else 0 }
        val out = hits.entries.sortedByDescending { it.value + (bi?.get(it.key)?.let { c -> 3000 * c } ?: 0) }
            .map { it.key }.filter { it != p }.take(n).toMutableList()
        if (out.size < n) for (u in extras()) {
            for (i in u.top(pb, n * 2 + 4)) { val w = u.word(i); if (w != p && w !in out && out.size < n) out.add(w) }
        }
        return out.map { matchCase(prefix, it) }
    }

    /** Typo candidates from the main list (+ learned words), best first. */
    private fun binCands(m: BinDict, w: String, prev: String?, limit: Double): List<Cand> {
        if (w.any { it.code > 127 }) return emptyList()
        val bi = prev?.let { bigram[it.lowercase()] }
        val out = ArrayList<Cand>()
        m.fuzzy(w.toByteArray(Charsets.UTF_8), limit) { i, cost ->
            val c = m.word(i)
            if (c != w) {
                val f = m.freq(i) + (user[c] ?: 0) * 50
                out.add(Cand(c, pop(f) - 3.0 * cost + (if (bi?.containsKey(c) == true) 2.0 else 0.0), cost, f))
            }
        }
        for ((c, f) in user) if (f >= 2 && abs(c.length - w.length) <= 2 && c != w && m.indexOf(c) < 0 && firstOk(w[0], c[0])) {
            val cost = wedit(w, c, limit)
            if (cost <= limit) out.add(Cand(c, pop(f * 50 + 500) - 3.0 * cost + (if (bi?.containsKey(c) == true) 2.0 else 0.0), cost, f * 50 + 500))
        }
        out.sortByDescending { it.score }
        return out
    }

    /**
     * Autocorrect with a big word list. A word the list marks as common is never touched. A rare list word (the
     * english-words list also holds misspellings such as "untill") or an Urban word is only fixed when the evidence is
     * strong: an adjacent-letter swap (hte -> the), or a long word one edit from a core word (accomodate -> accommodate).
     * Words the lists do not know are fixed as before.
     */
    private fun correctBin(m: BinDict, word: String, w: String, prev: String?): String? {
        if (w.length < 3 || w.any { it.code > 127 || it.isDigit() || !(it.isLetter() || it == '\'') }) return null
        if (word.drop(1).any { it.isUpperCase() } && word.any { it.isLowerCase() }) return null   // CamelCase / names
        val idx = m.indexOf(w)
        if (idx >= 0 && m.freq(idx) >= m.commonMin) return null
        if ((user[w] ?: 0) >= 2) return null
        val rare = idx >= 0
        val urban = !rare && urbanIdx(w) >= 0
        var cands = binCands(m, w, prev, 1.4)
        if (rare || urban) cands = cands.filter { it.f >= m.commonMin }
        if (urban) return cands.firstOrNull { isSwap(w, it.w) && it.f >= 1000 }?.w
        if (rare) return cands.firstOrNull { (isSwap(w, it.w) && it.f >= 1000) || (w.length >= 6 && it.cost <= 1.0) }?.w
        val best = cands.firstOrNull() ?: return null
        return if (best.cost <= (if (w.length == 3) 1.0 else 1.4)) best.w else null
    }

    private fun fixesBin(m: BinDict, word: String, w: String, n: Int, prev: String?): List<String> {
        val res = ArrayList<String>()
        for (c in binCands(m, w, prev, 1.4)) if (c.cost <= 1.4 && c.w != w && c.w !in res) res.add(c.w)
        if (w.none { it.code > 127 } && res.size < n) for (u in extras()) {
            val hits = ArrayList<Pair<String, Int>>()
            u.fuzzy(w.toByteArray(Charsets.UTF_8), 1.0) { i, _ -> hits.add(u.word(i) to u.freq(i)) }
            hits.sortByDescending { it.second }
            for ((c, _) in hits) if (c != w && c !in res && res.size < n) res.add(c)
        }
        return res.take(n).map { matchCase(word, it) }
    }

    private fun scanBin(b: BinDict, fc: Char, lastOk: BooleanArray, coreOnly: Boolean, fn: (String, Int) -> Unit) {
        if (fc.code > 127) return
        val r = b.range(byteArrayOf(fc.code.toByte()))
        val minF = if (coreOnly) b.commonMin else 0
        for (i in r[0] until r[1]) {
            val l = b.lastByte(i)
            if (l > 127 || !lastOk[l] || b.len(i) < 2) continue
            val f = b.freq(i)
            if (f >= minF) fn(b.word(i), f)
        }
    }

    // ---------------------------------------------------------------- next word
    fun next(prev: String, n: Int): List<String> {
        val l = bigram[prev.lowercase()]?.entries?.sortedByDescending { it.value }?.map { it.key }?.take(n) ?: emptyList()
        if (l.size >= n) return l
        return (l + defaultNext(prev.lowercase())).distinct().take(n)
    }

    private fun defaultNext(prev: String): List<String> = nextTab[prev] ?: when (prev) {
        "i" -> listOf("am", "have", "will", "think", "love")
        "i'm" -> listOf("not", "going", "so", "sorry", "happy")
        "thank" -> listOf("you")
        "how" -> listOf("are", "to", "much", "do")
        "what" -> listOf("is", "are", "do", "about")
        "good" -> listOf("morning", "night", "luck", "job")
        "see" -> listOf("you", "the", "it")
        "do" -> listOf("you", "not", "it")
        "you" -> listOf("are", "can", "have", "will")
        "it" -> listOf("is", "was", "will", "has")
        "the" -> listOf("same", "first", "best", "other")
        "to" -> listOf("be", "do", "the", "get")
        "i'll" -> listOf("be", "do", "see", "call")
        "can" -> listOf("you", "i", "we", "be")
        "will" -> listOf("be", "you", "do", "not")
        else -> top.filter { it in setOf("the", "to", "and", "i", "a", "you", "of", "in") }.take(4).ifEmpty { top.take(4) }
    }

    fun learn(word: String, prev: String?) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { it.isDigit() }) return
        user[w] = (user[w] ?: 0) + 1
        if (!prev.isNullOrEmpty()) bigram.getOrPut(prev.lowercase()) { HashMap() }.merge(w, 1) { a, b -> a + b }
        if (++dirty >= 10) flush()
    }

    // ---------------------------------------------------------------- gesture typing
    /**
     * Rank words by how closely the ideal path through their letters' key centres follows the finger path,
     * weighted by word frequency (and the previous word). Works even when keys were skipped between samples.
     */
    fun swipe(inp: SwipeInput, n: Int, prev: String? = null): List<String> {
        val pts = inp.pts
        if (pts.size < 4 || inp.centers.isEmpty()) return emptyList()
        val N = 32
        val user0 = resample(pts, N)
        val ulen = pathLen(pts)
        if (ulen < inp.keyW * 1.2f) return emptyList()
        val r = inp.keyW * 1.7f
        fun near(x: Float, y: Float) = inp.centers.filter { hypot(it.value[0] - x, it.value[1] - y) <= r }.keys
        val firsts = near(pts[0], pts[1]); val lasts = near(pts[pts.size - 2], pts[pts.size - 1])
        if (firsts.isEmpty() || lasts.isEmpty()) return emptyList()
        val bi = prev?.let { bigram[it.lowercase()] }
        val scored = ArrayList<Pair<String, Double>>()

        fun eval(w: String, f: Int) {
            if (w.length < 2 || w.last() !in lasts) return
            var k = 0; var ok = true; var prevCh = '\u0000'
            val ideal = FloatArray(w.length * 2)
            for (ch in w) {
                if (ch == prevCh) continue
                prevCh = ch
                val c = inp.centers[ch]; if (c == null) { ok = false; break }
                ideal[k++] = c[0]; ideal[k++] = c[1]
            }
            if (!ok || k < 4) return
            val path = ideal.copyOf(k)
            val ilen = pathLen(path)
            if (ilen < ulen * 0.45f || ilen > ulen * 1.8f) return
            val res = resample(path, N)
            var d = 0.0
            for (i in 0 until N) d += hypot(res[2 * i] - user0[2 * i], res[2 * i + 1] - user0[2 * i + 1])
            val shape = d / N / inp.keyW
            if (shape > 1.6) return
            val s = shape - ln(f.toDouble() + 2) * 0.07 - (if (bi?.containsKey(w) == true) 0.35 else 0.0)
            scored.add(w to s)
        }
        val mb = mainBin
        val lastOk = BooleanArray(128); for (c in lasts) if (c.code < 128) lastOk[c.code] = true
        val ubs = if (mb != null) extras() else emptyList()
        for (fc in firsts) {
            if (mb != null) {
                scanBin(mb, fc, lastOk, true) { w, f -> eval(w, f + (user[w] ?: 0) * 50) }
                for (ub in ubs) scanBin(ub, fc, lastOk, false) { w, f -> eval(w, f + (user[w] ?: 0) * 50) }
            } else {
                var i = lower(fc.toString())
                while (i < bw.size && bw[i][0] == fc) { val w = bw[i]; if (w.all { it.isLetter() }) eval(w, bf[i] + (user[w] ?: 0) * 50); i++ }
            }
            for ((w, f) in user.subMap(fc.toString(), fc.toString() + '\uffff')) if (f >= 2 && (mb?.indexOf(w) ?: baseIndex(w)) < 0 && w.all { it.isLetter() }) eval(w, f * 50 + 500)
        }
        return scored.sortedBy { it.second }.map { it.first }.distinct().take(n)
    }

    private fun pathLen(p: FloatArray): Float { var s = 0f; var i = 2; while (i < p.size) { s += hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]); i += 2 }; return s }

    private fun resample(p: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        val total = pathLen(p)
        if (total <= 0f) { for (i in 0 until n) { out[2 * i] = p[0]; out[2 * i + 1] = p[1] }; return out }
        val step = total / (n - 1)
        var seg = 2; var acc = 0f
        var x0 = p[0]; var y0 = p[1]
        out[0] = x0; out[1] = y0
        var idx = 1; var target = step
        while (idx < n - 1 && seg < p.size) {
            val x1 = p[seg]; val y1 = p[seg + 1]
            val d = hypot(x1 - x0, y1 - y0)
            if (acc + d >= target && d > 0f) {
                val t = (target - acc) / d
                x0 += (x1 - x0) * t; y0 += (y1 - y0) * t
                out[2 * idx] = x0; out[2 * idx + 1] = y0
                idx++; acc = target; target += step
            } else { acc += d; x0 = x1; y0 = y1; seg += 2 }
        }
        out[2 * (n - 1)] = p[p.size - 2]; out[2 * (n - 1) + 1] = p[p.size - 1]
        while (idx < n - 1) { out[2 * idx] = out[2 * (n - 1)]; out[2 * idx + 1] = out[2 * (n - 1) + 1]; idx++ }
        return out
    }

    // ---------------------------------------------------------------- persistence
    fun flush() {
        dirty = 0
        try { userFile.writeText(user.entries.joinToString("\n") { "${it.key}\t${it.value}" }) } catch (e: Exception) { }
        try {
            biFile.writeText(bigram.entries.flatMap { (a, m) -> m.entries.filter { it.value >= 1 }.map { "$a\t${it.key}\t${it.value}" } }.joinToString("\n"))
        } catch (e: Exception) { }
    }

    fun clearUser() { user.clear(); bigram.clear(); userFile.delete(); biFile.delete() }

    // ---------------------------------------------------------------- keyboard-aware edit distance
    private val dp = Array(40) { DoubleArray(40) }
    private fun firstOk(a: Char, b: Char) = a == b || adj(a, b)

    /** Weighted Damerau-Levenshtein: neighbour-key substitution 0.6, transposition 0.7, apostrophe 0.5, other 1.0/1.2. */
    private fun wedit(a: String, b: String, limit: Double): Double {
        val la = a.length; val lb = b.length
        if (abs(la - lb) > limit || la > 38 || lb > 38) return 99.0
        val d = dp
        for (i in 0..la) d[i][0] = i.toDouble()
        for (j in 0..lb) d[0][j] = j.toDouble()
        for (i in 1..la) {
            var rowMin = 99.0
            for (j in 1..lb) {
                val ca = a[i - 1]; val cb = b[j - 1]
                val sub = if (ca == cb) 0.0 else if (adj(ca, cb)) 0.6 else 1.2
                var v = d[i - 1][j - 1] + sub
                v = min(v, d[i - 1][j] + (if (ca == '\'') 0.5 else 1.0))
                v = min(v, d[i][j - 1] + (if (cb == '\'') 0.5 else 1.0))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, d[i - 2][j - 2] + 0.7)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return 99.0
        }
        return d[la][lb]
    }

    companion object {
        private val SKEL: Map<Char, Char> = HashMap<Char, Char>().also { m ->
            for ((g, to) in listOf("ইঈিী" to 'ি', "উঊুূ" to 'ু', "নণ" to 'ন', "শষস" to 'স', "তটৎ" to 'ত', "থঠ" to 'থ', "দড" to 'দ',
                "ধঢ" to 'ধ', "জয" to 'জ', "রড়ঢ়" to 'র', "অওো" to 'অ', "আা" to 'া', "এে" to 'ে', "ঐৈ" to 'ৈ', "ঔৌ" to 'ৌ',
                "ঋৃ" to 'ৃ', "ঙংঞ" to 'ং', "ঁঃ\u200c\u200d্" to '\u0000')) for (c in g) m[c] = to
        }
        private val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val offs = doubleArrayOf(0.0, 0.25, 0.75)
        private val adjTab = Array(26) { BooleanArray(26) }
        init {
            val pos = HashMap<Char, DoubleArray>()
            rows.forEachIndexed { r, s -> s.forEachIndexed { i, c -> pos[c] = doubleArrayOf(i + offs[r], r.toDouble()) } }
            for ((a, pa) in pos) for ((b, pb) in pos) if (a != b && hypot(pa[0] - pb[0], pa[1] - pb[1]) < 1.5) adjTab[a - 'a'][b - 'a'] = true
        }
        fun adj(a: Char, b: Char): Boolean = a in 'a'..'z' && b in 'a'..'z' && adjTab[a - 'a'][b - 'a']
    }
}
