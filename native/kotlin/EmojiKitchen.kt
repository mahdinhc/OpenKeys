package com.openkeys.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Emoji Kitchen: two emoji -> Google's mashup sticker (a PNG on gstatic.com).
 *
 * URL shape (the folder is the LEFT emoji, and each release lives under its own date):
 *   https://www.gstatic.com/android/keyboard/emojikitchen/20201001/u1f98a/u1f98a_u1f332.png
 * Multi-codepoint emoji are joined with '-' (u2764-ufe0f). Which date/order a pair lives under is not derivable from the
 * emoji, so we probe the known release folders (assets/emoji/kitchen_dates.txt) in both orders, in parallel, and cache
 * the result on disk so every pair is probed at most once.
 */
object EmojiKitchen {
    private const val BASE = "https://www.gstatic.com/android/keyboard/emojikitchen"
    private val FALLBACK_DATES = listOf("20230301", "20220823", "20220815", "20220406", "20220203", "20220110", "20211115", "20210831", "20210521", "20210218", "20201001")

    sealed class Result {
        class Ok(val file: File) : Result()
        object NoMix : Result()      // the pair doesn't exist in Emoji Kitchen
        object Offline : Result()    // couldn't reach the server (not cached as a miss)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "kitchen-probe").also { it.isDaemon = true } }
    private val misses = HashSet<String>()
    @Volatile private var dates: List<String>? = null

    /** 'u' + code points joined with '-', lowercase, no padding: 🦊 -> u1f98a, ❤️ -> u2764-ufe0f. */
    fun token(emoji: String): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < emoji.length) { val cp = emoji.codePointAt(i); out.add(Integer.toHexString(cp)); i += Character.charCount(cp) }
        return "u" + out.joinToString("-")
    }

    private fun tokenNoVs(emoji: String) = token(emoji.replace("\uFE0F", ""))

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

    private fun dir(ctx: Context) = ClipStore.dir(ctx)

    /** Same file for (a,b) and (b,a). */
    private fun cacheName(a: String, b: String): String {
        val (x, y) = listOf(tokenNoVs(a), tokenNoVs(b)).sorted()
        return "kitchen_${x}_$y.png"
    }

    fun cachedFile(ctx: Context, a: String, b: String): File? = File(dir(ctx), cacheName(a, b)).takeIf { it.exists() && it.length() > 0 }

    private fun candidates(ctx: Context, a: String, b: String): List<String> {
        fun forms(e: String) = listOf(token(e), tokenNoVs(e)).distinct()
        val out = ArrayList<String>()
        for ((l, r) in listOf(a to b, b to a))
            for (lf in forms(l)) for (rf in forms(r))
                for (d in datesList(ctx)) out.add(url(d, lf, rf))
        return out
    }

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
            // only a clean "not found" everywhere counts as a real miss; network trouble must not poison the cache
            if (failed.get() > 0 || latch.count > 0) return Result.Offline
            synchronized(misses) { misses.add(key) }
            return Result.NoMix
        }
        val f = File(dir(app), key)
        if (!download(u, f)) return Result.Offline
        prune(app)
        return Result.Ok(f)
    }

    /** Keep the sticker cache bounded (the files live in the clips folder so the FileProvider can share them). */
    private fun prune(ctx: Context) {
        val fs = dir(ctx).listFiles { f -> f.name.startsWith("kitchen_") }?.sortedByDescending { it.lastModified() } ?: return
        fs.drop(60).forEach { it.delete() }
    }
}
