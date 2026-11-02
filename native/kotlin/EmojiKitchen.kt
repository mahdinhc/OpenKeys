package com.openkeys.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Emoji Kitchen: two emoji -> Google's mashup sticker (a PNG on gstatic.com).
 *
 * Like emojikitchen.dev we use the community metadata (xsalazar/emoji-kitchen-backend, metadata.json) which lists EVERY valid
 * pair with the exact gstatic URL (release date + left/right order). We stream-parse it once into a compact, sorted,
 * fixed-width index on disk (filesDir/kitchen/index.bin, binary-searched without loading it into RAM), so
 *   - every new mix is supported the moment the metadata lists it,
 *   - "does this pair mix?" is answered offline and instantly (no more probing dozens of URLs),
 *   - the picker can show only the emoji that mix with the first one you pick.
 * Sticker PNGs are cached on disk (size-capped LRU, setting "kitchenCacheMb") so they load instantly and work offline.
 * If the index isn't downloaded yet we fall back to probing the known release folders (assets/emoji/kitchen_dates.txt).
 */
object EmojiKitchen {
    private const val BASE = "https://www.gstatic.com/android/keyboard/emojikitchen"
    const val META_URL = "https://raw.githubusercontent.com/xsalazar/emoji-kitchen-backend/main/app/metadata.json"
    private const val MAGIC = "OKKI1"
    private const val HEADER = 64
    private const val STALE_MS = 14L * 24 * 3600 * 1000
    private val FALLBACK_DATES = listOf("20260429", "20260202", "20230301", "20220823", "20220815", "20220406", "20220203", "20220110", "20211115", "20210831", "20210521", "20210218", "20201001")

    sealed class Result {
        class Ok(val file: File) : Result()
        object NoMix : Result()      // the pair doesn't exist in Emoji Kitchen
        object Offline : Result()    // couldn't reach the server (not cached as a miss)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "kitchen-probe").also { it.isDaemon = true } }
    private val misses = HashSet<String>()
    @Volatile private var dates: List<String>? = null
    private val building = AtomicBoolean(false)
    @Volatile private var lastError = ""
    @Volatile private var lastTry = 0L

    // ------------------------------------------------------------------ tokens
    /** 'u' + code points joined with '-', lowercase, no padding: 🦊 -> u1f98a, ❤️ -> u2764-fe0f. */
    fun token(emoji: String): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < emoji.length) { val cp = emoji.codePointAt(i); out.add(Integer.toHexString(cp)); i += Character.charCount(cp) }
        return "u" + out.joinToString("-")
    }

    private fun tokenNoVs(emoji: String) = token(emoji.replace("\uFE0F", ""))
    private fun norm(tok: String) = tok.replace("-fe0f", "")
    private fun forms(e: String) = listOf(token(e), tokenNoVs(e)).distinct()

    fun url(date: String, left: String, right: String) = "$BASE/$date/$left/${left}_$right.png"

    private fun datesList(ctx: Context): List<String> {
        dates?.let { return it }
        val l = try {
            ctx.applicationContext.assets.open("emoji/kitchen_dates.txt").bufferedReader().useLines { ls ->
                ls.map { it.trim() }.filter { it.matches(Regex("\\d{8}")) }.toList()
            }
        } catch (e: Exception) { emptyList() }
        return l.ifEmpty { FALLBACK_DATES }.also { dates = it }
    }

    // ------------------------------------------------------------------ sticker cache
    private fun dir(ctx: Context) = ClipStore.dir(ctx)
    private fun kdir(ctx: Context) = File(ctx.applicationContext.filesDir, "kitchen").also { it.mkdirs() }

    /** Same file for (a,b) and (b,a). */
    private fun cacheName(a: String, b: String): String {
        val (x, y) = listOf(tokenNoVs(a), tokenNoVs(b)).sorted()
        return "kitchen_${x}_$y.png"
    }

    fun cachedFile(ctx: Context, a: String, b: String): File? =
        File(dir(ctx), cacheName(a, b)).takeIf { it.exists() && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }

    private fun cacheFiles(ctx: Context) = dir(ctx).listFiles { f -> f.name.startsWith("kitchen_") && f.name.endsWith(".png") } ?: emptyArray()

    /** Keep the sticker cache under the user's limit (MB, 0 = unlimited), oldest-used first. */
    private fun prune(ctx: Context) {
        val mb = Prefs(ctx).int("kitchenCacheMb", 150)
        if (mb <= 0) return
        var total = 0L
        val fs = cacheFiles(ctx).sortedByDescending { it.lastModified() }
        val limit = mb * 1024L * 1024L
        for (f in fs) { total += f.length(); if (total > limit) f.delete() }
    }

    fun clearCache(ctx: Context) { cacheFiles(ctx).forEach { it.delete() }; synchronized(misses) { misses.clear() } }

    // ------------------------------------------------------------------ index
    private class Idx(val raf: RandomAccessFile, val w: Int, val n: Int, val built: Long)
    @Volatile private var idx: Idx? = null

    @Synchronized private fun openIdx(ctx: Context): Idx? {
        idx?.let { return it }
        val f = File(kdir(ctx), "index.bin")
        if (!f.exists() || f.length() < HEADER) return null
        return try {
            val raf = RandomAccessFile(f, "r")
            val hb = ByteArray(HEADER); raf.readFully(hb)
            val h = String(hb, Charsets.US_ASCII).trim()
            if (!h.startsWith(MAGIC)) { raf.close(); return null }
            fun field(k: String) = Regex("$k=(\\d+)").find(h)?.groupValues?.get(1)?.toLong() ?: 0L
            val w = field("W").toInt(); val n = field("N").toInt()
            if (w < 12 || n <= 0 || f.length() < HEADER + w.toLong() * n) { raf.close(); return null }
            Idx(raf, w, n, field("T")).also { idx = it }
        } catch (e: Exception) { null }
    }

    @Synchronized private fun closeIdx() { try { idx?.raf?.close() } catch (_: Exception) {}; idx = null }

    /** date folder for an exact "uLEFT_uRIGHT" key, or null. */
    private fun find(ix: Idx, key: String): String? = synchronized(ix) {
        var lo = 0; var hi = ix.n - 1
        val rec = ByteArray(ix.w)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            ix.raf.seek(HEADER + mid.toLong() * ix.w); ix.raf.readFully(rec)
            val k = String(rec, 0, ix.w - 9, Charsets.US_ASCII).trimEnd()
            val c = key.compareTo(k)
            if (c == 0) return String(rec, ix.w - 9, 8, Charsets.US_ASCII)
            if (c < 0) hi = mid - 1 else lo = mid + 1
        }
        null
    }

    private sealed class Lookup { class Found(val url: String) : Lookup(); object Missing : Lookup(); object NoIndex : Lookup() }

    private fun fromIndex(ctx: Context, a: String, b: String): Lookup {
        val ix = openIdx(ctx) ?: return Lookup.NoIndex
        for ((l, r) in listOf(a to b, b to a))
            for (lf in forms(l)) for (rf in forms(r)) {
                val d = find(ix, "${lf}_$rf") ?: continue
                return Lookup.Found(url(d, lf, rf))
            }
        return Lookup.Missing
    }

    fun hasIndex(ctx: Context) = openIdx(ctx) != null

    /** Normalised tokens of every emoji that mixes with [emoji] (null while there's no index). Scans the index sequentially. */
    fun partners(ctx: Context, emoji: String): Set<String>? {
        val ix = openIdx(ctx) ?: return null
        val me = forms(emoji).map { norm(it) }.toSet()
        val out = HashSet<String>()
        try {
            File(kdir(ctx), "index.bin").inputStream().buffered(1 shl 16).use { ins ->
                ins.skip(HEADER.toLong())
                val rec = ByteArray(ix.w)
                for (i in 0 until ix.n) {
                    var got = 0
                    while (got < ix.w) { val r = ins.read(rec, got, ix.w - got); if (r < 0) return out; got += r }
                    val k = String(rec, 0, ix.w - 9, Charsets.US_ASCII).trimEnd()
                    val u = k.indexOf('_'); if (u < 0) continue
                    val l = norm(k.substring(0, u)); val r = norm(k.substring(u + 1))
                    if (l in me) out.add(r)
                    if (r in me) out.add(l)
                }
            }
        } catch (e: Exception) { DevLog.e(ctx, "Kitchen", "partners scan failed", e) }
        return out
    }

    fun partnerToken(emoji: String) = norm(token(emoji))

    /** Downloads the metadata and rebuilds the index. Blocking: call from a background thread. */
    fun buildIndex(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!building.compareAndSet(false, true)) return false
        lastTry = System.currentTimeMillis()
        val tmp = File(kdir(app), "index.tmp")
        try {
            val custom = Prefs(app).str("kitchenMetaUrl", "").trim()
            val c = URL(if (custom.startsWith("http")) custom else META_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            if (c.responseCode != 200) { lastError = "Server answered ${c.responseCode}"; return false }
            val map = HashMap<String, String>(300_000)
            JsonReader(InputStreamReader(BufferedInputStream(c.inputStream, 1 shl 16), Charsets.UTF_8)).use { r -> readRoot(r, map) }
            c.disconnect()
            if (map.size < 1000) { lastError = "Metadata looked empty"; return false }
            val keys = map.keys.sorted()
            val w = keys.maxOf { it.length } + 9
            BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { o ->
                o.write("$MAGIC W=$w N=${keys.size} T=${System.currentTimeMillis()}".padEnd(HEADER - 1, ' ').plus("\n").toByteArray(Charsets.US_ASCII))
                for (k in keys) o.write((k.padEnd(w - 9, ' ') + map[k] + "\n").toByteArray(Charsets.US_ASCII).also { check(it.size == w) })
            }
            closeIdx()
            val dst = File(kdir(app), "index.bin")
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) { lastError = "Couldn't save index"; return false } }
            synchronized(misses) { misses.clear() }
            lastError = ""
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DevLog.e(app, "Kitchen", "index build failed", e)
            tmp.delete()
            return false
        } finally { building.set(false) }
    }

    private fun readRoot(r: JsonReader, map: HashMap<String, String>) {
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "data") {
                r.beginObject()
                while (r.hasNext()) { r.nextName(); readEmoji(r, map) }
                r.endObject()
            } else r.skipValue()
        }
        r.endObject()
    }

    private fun readEmoji(r: JsonReader, map: HashMap<String, String>) {
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "combinations") {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName(); r.beginArray()
                    while (r.hasNext()) readCombo(r, map)
                    r.endArray()
                }
                r.endObject()
            } else r.skipValue()
        }
        r.endObject()
    }

    private fun readCombo(r: JsonReader, map: HashMap<String, String>) {
        var u: String? = null; var latest = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "gStaticUrl" -> u = r.nextString()
                "isLatest" -> if (r.peek() == JsonToken.BOOLEAN) latest = r.nextBoolean() else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val s = u ?: return
        val i = s.indexOf("/emojikitchen/"); if (i < 0) return
        val parts = s.substring(i + 14).removeSuffix(".png").split('/')   // date / uLEFT / uLEFT_uRIGHT
        if (parts.size != 3 || parts[0].length != 8) return
        val key = parts[2]
        if (latest || !map.containsKey(key)) map[key] = parts[0]
    }

    /** Refreshes the index in the background when missing or older than two weeks (at most once per 10 min). */
    fun ensureIndex(ctx: Context) {
        val app = ctx.applicationContext
        val age = openIdx(app)?.let { System.currentTimeMillis() - it.built }
        if (age != null && age < STALE_MS) return
        if (building.get() || System.currentTimeMillis() - lastTry < 10 * 60 * 1000) return
        Thread { buildIndex(app) }.start()
    }

    fun statusJson(ctx: Context): String {
        val ix = openIdx(ctx)
        val fs = cacheFiles(ctx)
        return JSONObject().put("indexed", ix != null).put("pairs", ix?.n ?: 0).put("builtAt", ix?.built ?: 0L)
            .put("building", building.get()).put("error", lastError)
            .put("cacheFiles", fs.size).put("cacheBytes", fs.sumOf { it.length() }).toString()
    }

    // ------------------------------------------------------------------ mixing
    private fun status(u: String, method: String): Int {
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = true
            c.responseCode
        } finally { c.disconnect() }
    }

    private fun download(u: String, to: File): Boolean {
        val tmp = File(to.path + ".part")
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000; c.readTimeout = 15000
            if (c.responseCode != 200) return false
            c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            tmp.renameTo(to) && to.length() > 0
        } catch (e: Exception) { false } finally { c.disconnect(); if (tmp.exists()) tmp.delete() }
    }

    /** Resolves a mix in the background; [done] runs on the main thread. */
    fun mix(ctx: Context, a: String, b: String, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        val key = cacheName(a, b)
        cachedFile(app, a, b)?.let { done(Result.Ok(it)); return }
        if (synchronized(misses) { key in misses }) { done(Result.NoMix); return }
        Thread {
            val res = try { resolve(app, a, b, key) } catch (e: Exception) { DevLog.e(app, "Kitchen", "mix failed", e); Result.Offline }
            main.post { done(res) }
        }.start()
    }

    private fun resolve(app: Context, a: String, b: String, key: String): Result {
        when (val l = fromIndex(app, a, b)) {
            is Lookup.Found -> {
                val f = File(dir(app), key)
                if (download(l.url, f)) { prune(app); return Result.Ok(f) }
                // couldn't fetch the listed file: if the server is reachable it's gone, otherwise we're offline
                val code = try { status(l.url, "HEAD") } catch (e: Exception) { return Result.Offline }
                if (code !in 400..499) return Result.Offline
            }
            Lookup.Missing -> { synchronized(misses) { misses.add(key) }; return Result.NoMix }
            Lookup.NoIndex -> ensureIndex(app)
        }
        return probe(app, a, b, key)
    }

    private fun candidates(ctx: Context, a: String, b: String): List<String> {
        val out = ArrayList<String>()
        for ((l, r) in listOf(a to b, b to a))
            for (lf in forms(l)) for (rf in forms(r))
                for (d in datesList(ctx)) out.add(url(d, lf, rf))
        return out
    }

    /** Fallback when there's no index yet: HEAD every known release folder in both orders. */
    private fun probe(app: Context, a: String, b: String, key: String): Result {
        val urls = candidates(app, a, b)
        val hit = AtomicReference<String?>(null)
        val failed = AtomicInteger(0)
        val latch = CountDownLatch(urls.size)
        for (u in urls) pool.execute {
            try {
                if (hit.get() == null) {
                    val code = status(u, "HEAD")
                    if (code == 200) hit.compareAndSet(null, u) else if (code !in 400..499) failed.incrementAndGet()
                }
            } catch (e: Exception) { failed.incrementAndGet() } finally { latch.countDown() }
        }
        latch.await(25, TimeUnit.SECONDS)
        val u = hit.get()
        if (u == null) {
            if (failed.get() > 0 || latch.count > 0 || hasIndex(app).not() && !isOnline(app)) return Result.Offline
            // without an index a clean 404 everywhere is only remembered for this session
            synchronized(misses) { misses.add(key) }
            return Result.NoMix
        }
        val f = File(dir(app), key)
        if (!download(u, f)) return Result.Offline
        prune(app)
        return Result.Ok(f)
    }

    private fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork != null
    } catch (e: Exception) { true }

    // ------------------------------------------------------------------ optional prefetch
    @Volatile private var prefetchGen = 0

    /** Pre-downloads (up to [limit]) uncached mixes of [emoji] with [others] so they open instantly / offline. Cancelled by the next call. */
    fun prefetch(ctx: Context, emoji: String, others: List<String>, limit: Int = 150) {
        val app = ctx.applicationContext
        val gen = ++prefetchGen
        Thread {
            var n = 0
            for (o in others) {
                if (gen != prefetchGen || n >= limit) return@Thread
                if (cachedFile(app, emoji, o) != null) continue
                val key = cacheName(emoji, o)
                val l = fromIndex(app, emoji, o) as? Lookup.Found ?: continue
                if (download(l.url, File(dir(app), key))) n++ else if (!isOnline(app)) return@Thread
            }
            if (n > 0) prune(app)
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
