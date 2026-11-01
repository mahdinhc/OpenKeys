package com.openkeys.keyboard

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Read-only word list that lives in a memory-mapped file ("OKD1", written by scripts/build_dict_bin.py).
 *
 * Nothing is parsed or copied into the Java heap when it is opened, so start-up time and memory do not depend on how
 * many words the file holds; the OS pages in only what a lookup touches. Words are sorted by their UTF-8 bytes, which
 * gives:
 *   - exact lookup and prefix ranges in O(log n),
 *   - the best completions for 1..3 letter prefixes straight from a table built at build time,
 *   - typo candidates through a pruned walk over the sorted words (a trie in disguise) that visits roughly a
 *     thousand prefixes instead of every word.
 */
class BinDict private constructor(private val b: MappedByteBuffer) {
    val size: Int
    /** Words with a frequency >= this are "core" words; the rest are valid but rare (weak evidence for autocorrect). */
    val commonMin: Int
    /** The most common words, best first (used as the next-word fallback). */
    val fallback: IntArray
    private val oOffsets: Int
    private val oFreq: Int
    private val oBlob: Int
    private val oPK: Int
    private val nPref: Int
    private val oPS: Int
    private val oPW: Int

    init {
        b.order(ByteOrder.LITTLE_ENDIAN)
        if (b.capacity() < HEADER || b.getInt(0) != MAGIC || b.getInt(4) != 1 || b.getInt(52) != b.capacity()) throw IOException("bad dictionary file")
        size = b.getInt(8)
        oOffsets = b.getInt(12); oFreq = b.getInt(16); oBlob = b.getInt(20)
        oPK = b.getInt(28); nPref = b.getInt(32); oPS = b.getInt(36); oPW = b.getInt(40)
        val oFb = b.getInt(44)
        commonMin = b.getInt(48)
        val fc = b.getInt(oFb)
        fallback = IntArray(fc) { b.getInt(oFb + 4 + 4 * it) }
    }

    // ------------------------------------------------------------------ raw access
    private fun off(i: Int) = b.getInt(oOffsets + 4 * i)
    fun len(i: Int) = off(i + 1) - off(i)
    fun freq(i: Int) = b.getInt(oFreq + 4 * i)
    private fun byteAt(i: Int, k: Int): Int = b.get(oBlob + off(i) + k).toInt() and 0xFF
    fun lastByte(i: Int): Int = b.get(oBlob + off(i + 1) - 1).toInt() and 0xFF

    fun word(i: Int): String {
        val s = off(i); val n = off(i + 1) - s
        val cs = CharArray(n)
        var ascii = true
        for (k in 0 until n) {
            val v = b.get(oBlob + s + k).toInt()
            if (v < 0) { ascii = false; break }
            cs[k] = v.toChar()
        }
        if (ascii) return String(cs)
        val bytes = ByteArray(n)
        for (k in 0 until n) bytes[k] = b.get(oBlob + s + k)
        return String(bytes, Charsets.UTF_8)
    }

    /** Unsigned lexicographic comparison of word [i] with [p]. */
    private fun cmp(i: Int, p: ByteArray): Int {
        val s = oBlob + off(i); val n = off(i + 1) - off(i); val m = p.size
        val l = if (n < m) n else m
        for (k in 0 until l) {
            val x = b.get(s + k).toInt() and 0xFF; val y = p[k].toInt() and 0xFF
            if (x != y) return x - y
        }
        return n - m
    }

    private fun startsWith(i: Int, p: ByteArray): Boolean {
        val s = oBlob + off(i)
        if (off(i + 1) - off(i) < p.size) return false
        for (k in p.indices) if (b.get(s + k) != p[k]) return false
        return true
    }

    // ------------------------------------------------------------------ lookup
    fun lowerBound(p: ByteArray): Int {
        var lo = 0; var hi = size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (cmp(m, p) < 0) lo = m + 1 else hi = m }
        return lo
    }

    fun indexOf(p: ByteArray): Int { val i = lowerBound(p); return if (i < size && cmp(i, p) == 0) i else -1 }
    fun indexOf(w: String): Int = indexOf(w.toByteArray(Charsets.UTF_8))
    fun contains(w: String) = indexOf(w) >= 0

    /** [lo, hi) of the words that start with [p]. */
    fun range(p: ByteArray): IntArray {
        val lo = lowerBound(p)
        var a = lo; var h = size
        while (a < h) { val m = (a + h) ushr 1; if (startsWith(m, p)) a = m + 1 else h = m }
        return intArrayOf(lo, a)
    }

    /** Indices of up to [k] most frequent words that start with [p] (best first). */
    fun top(p: ByteArray, k: Int): IntArray {
        val len = p.size
        if (len == 0 || k <= 0) return EMPTY
        if (len <= 3) {
            val key = (len shl 24) or ((p[0].toInt() and 0xFF) shl 16) or
                (if (len > 1) (p[1].toInt() and 0xFF) shl 8 else 0) or (if (len > 2) (p[2].toInt() and 0xFF) else 0)
            var lo = 0; var hi = nPref
            while (lo < hi) { val m = (lo + hi) ushr 1; if (b.getInt(oPK + 4 * m) < key) lo = m + 1 else hi = m }
            if (lo >= nPref || b.getInt(oPK + 4 * lo) != key) return EMPTY
            val s = b.getInt(oPS + 4 * lo); val e = b.getInt(oPS + 4 * (lo + 1))
            val n = if (e - s < k) e - s else k
            return IntArray(n) { b.getInt(oPW + 4 * (s + it)) }
        }
        val r = range(p)
        val idx = IntArray(k); val fr = IntArray(k); var cnt = 0
        for (i in r[0] until r[1]) {
            val f = freq(i)
            if (cnt == k && f <= fr[k - 1]) continue
            var j = if (cnt < k) cnt++ else k - 1
            while (j > 0 && fr[j - 1] < f) { fr[j] = fr[j - 1]; idx[j] = idx[j - 1]; j-- }
            fr[j] = f; idx[j] = i
        }
        return idx.copyOf(cnt)
    }

    // ------------------------------------------------------------------ typo candidates
    /**
     * Reports every word whose keyboard-aware edit distance from [a] (lower-case ASCII, apostrophes allowed) is <= [limit].
     * Costs: neighbour-key substitution 0.6, other substitution 1.2, adjacent swap 0.5, a doubled letter dropped or added
     * 0.6 (helo/hello, untill/until), apostrophe 0.5, any other insertion/deletion 1.0. The first letter must match,
     * be a neighbour key, or be the second typed letter (hte/the).
     * The walk keeps one edit-distance column per prefix depth and abandons a branch as soon as no word below it can
     * come within [limit], so the cost does not grow with the size of the dictionary.
     */
    fun fuzzy(a: ByteArray, limit: Double, onHit: (Int, Double) -> Unit) {
        if (a.isEmpty() || a.size > 38 || size == 0) return
        for (x in a) if (x < 0) return
        Fz(a, limit, onHit).run()
    }

    private inner class Fz(val a: ByteArray, val limit: Double, val onHit: (Int, Double) -> Unit) {
        val m = a.size
        val maxD = m + (limit / 0.5).toInt()
        val cols = Array(maxD + 3) { DoubleArray(m + 1) }
        val cmin = DoubleArray(maxD + 3)
        val path = IntArray(maxD + 3)
        val dele = DoubleArray(m + 1)

        fun run() {
            for (i in 1..m) {
                dele[i] = if (a[i - 1].toInt() == 39) 0.5 else if (i >= 2 && a[i - 1] == a[i - 2]) 0.6 else 1.0
                cols[0][i] = cols[0][i - 1] + dele[i]
            }
            cmin[0] = 0.0
            dfs(0, size, 0)
        }

        fun firstOk(ch: Int): Boolean {
            val a0 = a[0].toInt()
            return ch == a0 || (ch < 128 && ADJ[a0][ch]) || (m > 1 && ch == a[1].toInt())
        }

        /** End of the run of words in [lo, hi) that have byte [ch] at position [d] (all of them are longer than d). */
        fun groupEnd(lo: Int, hi: Int, d: Int, ch: Int): Int {
            var x = lo; var step = 1
            while (x + step < hi && byteAt(x + step, d) == ch) { x += step; step = step shl 1 }
            var l = x + 1; var h = if (hi < x + step + 1) hi else x + step + 1
            while (l < h) { val mid = (l + h) ushr 1; if (byteAt(mid, d) == ch) l = mid + 1 else h = mid }
            return l
        }

        /** Words in [lo0, hi) all share the first [d] letters; cols[d] is the finished column for that prefix. */
        fun dfs(lo0: Int, hi: Int, d: Int) {
            var lo = lo0
            if (len(lo) == d) {
                val c = cols[d][m]
                if (c <= limit) onHit(lo, c)
                lo++
            }
            if (d >= maxD) return
            while (lo < hi) {
                val ch = byteAt(lo, d)
                val e = groupEnd(lo, hi, d, ch)
                if (d != 0 || firstOk(ch)) {
                    path[d] = ch
                    val j = d + 1
                    val prev = cols[j - 1]; val cur = cols[j]
                    val insc = if (ch == 39) 0.5 else if (d >= 1 && ch == path[d - 1]) 0.6 else 1.0
                    cur[0] = prev[0] + insc
                    var mn = cur[0]
                    for (i in 1..m) {
                        val ca = a[i - 1].toInt()
                        val sub = if (ca == ch) 0.0 else if (ca < 128 && ch < 128 && ADJ[ca][ch]) 0.6 else 1.2
                        var v = prev[i - 1] + sub
                        val v2 = prev[i] + insc
                        if (v2 < v) v = v2
                        val v3 = cur[i - 1] + dele[i]
                        if (v3 < v) v = v3
                        if (i > 1 && j > 1 && ca == path[j - 2] && a[i - 2].toInt() == ch) {
                            val v4 = cols[j - 2][i - 2] + 0.5
                            if (v4 < v) v = v4
                        }
                        cur[i] = v
                        if (v < mn) mn = v
                    }
                    cmin[j] = mn
                    // a swap skips one column, so a branch only dies when two consecutive columns are out of reach
                    if ((if (mn < cmin[d]) mn else cmin[d]) <= limit) dfs(lo, e, j)
                }
                lo = e
            }
        }
    }

    companion object {
        private const val MAGIC = 0x31444B4F          // "OKD1"
        private const val HEADER = 64
        private val EMPTY = IntArray(0)
        private val ADJ = Array(128) { x -> BooleanArray(128) { y -> WordDict.adj(x.toChar(), y.toChar()) } }

        /**
         * Opens assets/[asset] ("dict/en.bin"). The asset is copied once to filesDir/dict/ (again only when the dictionary
         * in the APK changes) and mapped from there. Returns null when the asset does not exist or cannot be used.
         */
        fun open(ctx: Context, asset: String): BinDict? = try {
            val dir = File(ctx.filesDir, "dict"); dir.mkdirs()
            val f = File(dir, asset.substringAfterLast('/'))
            ensureCopy(ctx, asset, f)
            RandomAccessFile(f, "r").use { raf -> BinDict(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())) }
        } catch (e: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            DevLog.e(ctx, "Dict", "cannot open $asset", e); null
        }

        private fun readFully(ins: InputStream, buf: ByteArray) {
            var n = 0
            while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) throw IOException("short read"); n += r }
        }

        private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun ensureCopy(ctx: Context, asset: String, f: File) {
            val want = ByteArray(HEADER)
            ctx.assets.open(asset).use { readFully(it, want) }          // throws FileNotFoundException when there is no such asset
            if (f.exists() && f.length() == le(want, 52).toLong()) {
                val have = ByteArray(HEADER)
                try { f.inputStream().use { readFully(it, have) }; if (have.contentEquals(want)) return } catch (e: IOException) { }
            }
            val tmp = File(f.path + ".tmp")
            ctx.assets.open(asset).use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("cannot install $asset") }
        }
    }
}
