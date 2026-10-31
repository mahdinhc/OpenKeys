package com.openkeys.keyboard

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import java.util.Locale

class EmojiItem(val s: String, val group: Int, val mod: Boolean, val kw: String)

/** Full RGI emoji set (assets/emoji/emoji.txt) filtered to what this device can actually draw. */
object EmojiData {
    val GROUPS = listOf("Smileys & Emotions", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags")
    val GROUP_ICONS = listOf("emoji", "person", "paw", "restaurant", "car", "trophy", "bulb", "tag", "flag")

    @Volatile var items: List<EmojiItem> = emptyList()
    @Volatile private var toneSet: Set<String> = emptySet()
    @Volatile private var loading = false
    private val waiting = ArrayList<() -> Unit>()

    /** Loads in the background; [done] runs on the main thread (immediately if already loaded). */
    fun load(ctx: Context, done: (() -> Unit)? = null) {
        val main = Handler(Looper.getMainLooper())
        if (items.isNotEmpty()) { done?.invoke(); return }
        synchronized(waiting) { if (done != null) waiting.add(done) }
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            val out = ArrayList<EmojiItem>()
            try {
                val paint = Paint()
                app.assets.open("emoji/emoji.txt").bufferedReader().useLines { lines ->
                    for (ln in lines) {
                        val p = ln.split("\t")
                        if (p.size < 3) continue
                        val s = p[1]
                        if (!paint.hasGlyph(s)) continue
                        var kw = p.getOrElse(3) { "" }
                        val cps = s.codePoints().toArray()
                        if (cps.size == 2 && cps[0] in 0x1F1E6..0x1F1FF && cps[1] in 0x1F1E6..0x1F1FF) {
                            val code = "" + ('A' + (cps[0] - 0x1F1E6)) + ('A' + (cps[1] - 0x1F1E6))
                            kw = "flag " + Locale("", code).getDisplayCountry(Locale.ENGLISH).lowercase() + " " + code.lowercase()
                        }
                        out.add(EmojiItem(s, p[0].toInt(), p[2] == "1", kw))
                    }
                }
            } catch (e: Exception) { DevLog.e(app, "Emoji", "emoji.txt failed to load", e) }
            toneSet = out.filter { it.mod }.map { it.s }.toHashSet()
            items = out
            main.post {
                loading = false
                val cbs = synchronized(waiting) { val l = ArrayList(waiting); waiting.clear(); l }
                cbs.forEach { it() }
            }
        }.start()
    }

    fun withTone(s: String, tone: Int): String {
        val first = s.codePointAt(0)
        var rest = s.substring(Character.charCount(first))
        if (rest.startsWith("\uFE0F")) rest = rest.substring(1)
        return String(Character.toChars(first)) + String(Character.toChars(0x1F3FB + tone)) + rest
    }

    /** Default + five skin tones, only those this device can draw. */
    fun variants(s: String): List<String> {
        val p = Paint()
        return listOf(s) + (0..4).map { withTone(s, it) }.filter { p.hasGlyph(it) }
    }

    fun isToneBase(s: String) = toneSet.contains(s)

    fun search(q: String, limit: Int = 80): List<String> {
        val toks = q.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<EmojiItem, Int>>()
        for (it in items) {
            var score = 0
            var ok = true
            val words = it.kw.split(" ")
            for (t in toks) {
                if (words.contains(t)) score += 3
                else if (words.any { w -> w.startsWith(t) }) score += 2
                else if (it.kw.contains(t)) score += 1
                else { ok = false; break }
            }
            if (ok) scored.add(it to score)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first.s }
    }
}
