package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.max
import kotlin.math.min

// ======================================================================================================== data
class StickerItem(val a: String, val b: String) { val key: String = EmojiKitchen.pairKey(a, b) }
class StickerSection(val items: List<StickerItem>)

/** Orders everything that mixes with an emoji: its own double-mix, then emoji you use a lot, then the same category, then the rest. */
object KitchenRank {
    /** [self] = the emoji mixes with itself. [top] = best picks (starts with the emoji itself when [self]); [rest] = every other partner. */
    class Result(val self: Boolean, val top: List<String>, val rest: List<String>)

    /** Blocking (the first call for an emoji scans the index). Needs [EmojiData] loaded and the pair index; null otherwise. */
    fun rank(ctx: Context, emoji: String, recents: List<String>, topN: Int = 8): Result? {
        val set = EmojiKitchen.partners(ctx, emoji) ?: return null
        val items = EmojiData.items
        if (items.isEmpty()) return null
        val me = EmojiKitchen.partnerToken(emoji)
        val order = ArrayList<EmojiItem>()
        val byTok = HashMap<String, String>()
        var group: Int? = null
        for (it in items) {
            if (it.group >= 8) continue
            val t = EmojiKitchen.partnerToken(it.s)
            if (t == me) group = it.group
            if (t in set) { order.add(it); byTok[t] = it.s }
        }
        val self = me in set
        val top = LinkedHashSet<String>()
        if (self) top.add(emoji)
        fun room() = top.size < topN
        for (r in recents) {                                            // 1) what you use
            if (!room()) break
            val t = EmojiKitchen.partnerToken(EmojiKitchen.base(r))
            if (t != me) byTok[t]?.let { top.add(it) }
        }
        if (group != null) for (it in order) {                          // 2) same category, keyboard order
            if (!room()) break
            if (it.group == group && EmojiKitchen.partnerToken(it.s) != me) top.add(it.s)
        }
        for (it in order) {                                             // 3) anything
            if (!room()) break
            if (EmojiKitchen.partnerToken(it.s) != me) top.add(it.s)
        }
        val rest = order.map { it.s }.filter { it !in top && EmojiKitchen.partnerToken(it) != me }
        return Result(self, top.toList(), rest)
    }
}

// ======================================================================================================== loader
/** Loads Emoji Kitchen stickers off the main thread into a small memory cache. Newest request first; scrolled-away cells are skipped. */
object StickerLoader {
    private class Task(val ctx: Context, val a: String, val b: String, val px: Int, val key: String, val ready: Runnable)

    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val lock = Any()
    private val inflight = HashSet<String>()
    private val wanted = HashMap<String, Long>()
    private val failed = HashMap<String, Long>()
    private val queue = LinkedBlockingDeque<Task>()
    @Volatile private var started = false

    fun bucket(px: Int) = ((max(px, 32) + 63) / 64) * 64

    private fun start() {
        if (started) return
        synchronized(lock) {
            if (started) return
            started = true
            repeat(3) {
                Thread({
                    while (true) {
                        val t = try { queue.takeFirst() } catch (e: InterruptedException) { return@Thread }
                        process(t)
                    }
                }, "sticker-load").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
            }
        }
    }

    private fun process(t: Task) {
        val now = SystemClock.uptimeMillis()
        val age = synchronized(lock) { now - (wanted[t.key] ?: 0L) }
        if (age > 1500) {                                               // scrolled away while waiting: drop it, a redraw asks again if it's visible
            synchronized(lock) { inflight.remove(t.key) }
            main.post(t.ready); return
        }
        var bmp: Bitmap? = null
        try {
            val f = EmojiKitchen.fetch(t.ctx, t.a, t.b)
            if (f != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= t.px && o.outHeight / (s * 2) >= t.px) s *= 2
                bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            }
        } catch (e: Throwable) { }
        synchronized(lock) {
            inflight.remove(t.key)
            if (bmp != null) mem.put(t.key, bmp) else failed[t.key] = SystemClock.uptimeMillis()
        }
        main.post(t.ready)
    }

    /** The sticker if it is in memory, otherwise null (and it starts loading; [ready] runs on the main thread when something changed). */
    fun get(ctx: Context, a: String, b: String, px: Int, ready: Runnable): Bitmap? {
        val bp = bucket(px)
        val key = EmojiKitchen.pairKey(a, b) + "@" + bp
        mem.get(key)?.let { return it }
        start()
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            wanted[key] = now
            val fl = failed[key]
            if (fl != null) { if (now - fl < 20000) return null else failed.remove(key) }
            if (!inflight.add(key)) return null
            queue.addFirst(Task(ctx.applicationContext, a, b, bp, key, ready))
        }
        return null
    }
}

// ======================================================================================================== banner (row in the emoji panel)
/** The "Emoji Kitchen" row above Recent Emoji: [double mix] | a few mixes of your last emoji | [>] opens Browse. */
class KitchenBanner(
    private val ctx: Context,
    private val invalidate: () -> Unit,
    private val onSend: (String, String) -> Unit,
    private val onFav: (String, String) -> Unit,
    private val onBrowse: (String) -> Unit
) : GridBanner {
    var theme: Theme = Theme.DARK
    private var focus = ""
    private var self = false
    private var partners: List<String> = emptyList()
    private val slots = ArrayList<Pair<RectF, Pair<String, String>>>()
    private val chev = RectF()
    private var divX = -1f
    private var laidW = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ready = Runnable { invalidate() }

    fun set(emoji: String, self: Boolean, partners: List<String>) { focus = emoji; this.self = self; this.partners = partners; laidW = -1f }

    private fun headerH() = ctx.dpf(34f)
    private fun rowH() = ctx.dpf(76f)
    override fun height(w: Float) = headerH() + rowH() + ctx.dpf(4f)

    private fun layout(w: Float) {
        if (w == laidW) return
        laidW = w; slots.clear(); divX = -1f
        val m = ctx.dpf(10f)
        val top = headerH(); val h = rowH()
        var x = m
        if (self) {
            val big = ctx.dpf(68f)
            slots.add(RectF(x, top + (h - big) / 2, x + big, top + (h + big) / 2) to (focus to focus))
            x += big + ctx.dpf(8f); divX = x; x += ctx.dpf(10f)
        }
        val cd = ctx.dpf(40f)
        chev.set(w - m - cd, top + (h - cd) / 2, w - m, top + (h + cd) / 2)
        val small = ctx.dpf(54f)
        val avail = chev.left - ctx.dpf(8f) - x
        val n = min(partners.size, max(1, (avail / (small + ctx.dpf(4f))).toInt()))
        if (n > 0) {
            val step = avail / n
            for (i in 0 until n) {
                val cx = x + step * i + step / 2
                slots.add(RectF(cx - small / 2, top + (h - small) / 2, cx + small / 2, top + (h + small) / 2) to (focus to partners[i]))
            }
        }
    }

    override fun draw(c: Canvas, top: Float, w: Float) {
        layout(w)
        paint.typeface = null; paint.color = theme.hint; paint.textSize = ctx.dpf(14f); paint.textAlign = Paint.Align.LEFT
        c.drawText("Emoji Kitchen", ctx.dpf(12f), top + headerH() * 0.68f, paint)
        for ((r0, pair) in slots) {
            val r = RectF(r0); r.offset(0f, top)
            val bmp = StickerLoader.get(ctx, pair.first, pair.second, r.width().toInt(), ready)
            if (bmp != null) c.drawBitmap(bmp, null, r, bmpPaint)
            else { paint.color = theme.key; paint.alpha = 90; c.drawRoundRect(r, ctx.dpf(14f), ctx.dpf(14f), paint); paint.alpha = 255 }
        }
        if (divX >= 0) {
            paint.color = theme.hint; paint.alpha = 90; paint.strokeWidth = ctx.dpf(1f)
            c.drawLine(divX, top + headerH() + ctx.dpf(10f), divX, top + headerH() + rowH() - ctx.dpf(10f), paint); paint.alpha = 255
        }
        paint.color = theme.key; paint.style = Paint.Style.FILL
        c.drawCircle(chev.centerX(), chev.centerY() + top, chev.width() / 2, paint)
        Icons.draw(c, "chevron_right", chev.centerX(), chev.centerY() + top, ctx.dpf(24f), theme.keyText)
    }

    override fun tap(x: Float, y: Float, w: Float): Boolean {
        layout(w)
        val slop = ctx.dpf(8f)
        if (RectF(chev).apply { inset(-slop, -slop) }.contains(x, y)) { onBrowse(focus); return true }
        for ((r, p) in slots) if (r.contains(x, y)) { onSend(p.first, p.second); return true }
        return false
    }

    override fun hold(x: Float, y: Float, w: Float): Boolean {
        layout(w)
        for ((r, p) in slots) if (r.contains(x, y)) { onFav(p.first, p.second); return true }
        return false
    }
}

// ======================================================================================================== mix card (pinned over the emoji grid)
/**
 * Gboard-style mix: the last two emoji you tapped wind up, fly together, collide and burst into their Emoji Kitchen sticker
 * (tap = send, hold = favourite). When the pair has no mix they bounce apart and shake their heads.
 *
 * Timeline (every duration is scaled by the system "animator duration scale", 0 = no animation):
 *   enter   card fades / drops in
 *   approach  wind-up (they lean back), then dash at each other along a little arc, tilting in
 *   wait    overlapped, pulsing + a ripple, while the sticker loads (instant when it is cached)
 *   pop     emoji squash and vanish, shock ring + sparkles, the sticker springs out with a wobble, hint fades in
 *   bounce  (no mix) they spring apart, tilt away and the message fades in
 */
class KitchenMixCard(
    private val ctx: Context,
    private val invalidate: () -> Unit,
    private val onSend: (String, String) -> Unit,
    private val onFav: (String, String) -> Unit,
    private val onClose: () -> Unit
) : GridBanner {
    var theme: Theme = Theme.DARK
    /** Called once when the sticker pops out (the panel adds a light haptic tick). */
    var onPop: (() -> Unit)? = null

    private var a = ""
    private var b = ""
    private var seq = 0
    private var t0 = 0L                       // when the animation started
    private var tRes = 0L                     // when the answer arrived (0 = still waiting)
    private var res: EmojiKitchen.Result? = null
    private var bmp: Bitmap? = null
    private var popped = false
    private var favT = 0L                     // heart burst after a long-press
    private var favOn = true
    private var k = 1f                        // animation speed factor (system setting)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val card = RectF()
    private val sticker = RectF()
    private val close = RectF()
    private val tmp = RectF()

    private fun d(ms: Int) = max(1L, (ms * k).toLong())
    private val enterMs get() = d(200)
    private val windMs get() = d(70)
    private val approachMs get() = d(340)
    private val popMs get() = d(560)
    private val bounceMs get() = d(520)

    fun show(a: String, b: String) {
        this.a = a; this.b = b
        val my = ++seq
        k = try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceIn(0f, 3f) } catch (e: Throwable) { 1f }
        t0 = SystemClock.uptimeMillis(); tRes = 0L; res = null; bmp = null; popped = false; favT = 0L
        EmojiKitchen.mix(ctx, a, b) { r ->
            if (my != seq) return@mix                          // the user already tapped something else
            val f = (r as? EmojiKitchen.Result.Ok)?.file
            val dec = if (f != null) decode(f) else null
            bmp = dec
            res = if (f != null && dec == null) EmojiKitchen.Result.Offline else r
            tRes = SystemClock.uptimeMillis()
            invalidate()
        }
        invalidate()
    }

    fun cancel() { seq++ }

    /** Heart burst on the sticker after it was added to (or removed from) the favourites. */
    fun markFav(on: Boolean) { favOn = on; favT = SystemClock.uptimeMillis(); invalidate() }

    private fun decode(f: java.io.File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 256 && o.outHeight / (s * 2) >= 256) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    override fun height(w: Float) = ctx.dpf(88f)

    private fun layout(w: Float) {
        val m = ctx.dpf(8f)
        card.set(m, ctx.dpf(4f), w - m, ctx.dpf(84f))
        val s = ctx.dpf(64f); val cx = w / 2f; val cy = ctx.dpf(44f)
        sticker.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        val cs = ctx.dpf(36f); val pad = ctx.dpf(2f)
        close.set(card.right - cs - pad, card.top + pad, card.right - pad, card.top + pad + cs)
    }

    // ---- easing
    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    private fun easeOut(t: Float): Float { val u = 1f - clamp01(t); return 1f - u * u * u }
    private fun easeInBack(t: Float): Float { val c = 1.5f; val x = clamp01(t); return x * x * ((c + 1f) * x - c) }
    /** Damped spring 0 -> ~1.18 -> 1. */
    private fun spring(t: Float): Float { val x = clamp01(t); return 1f - Math.exp(-6.0 * x).toFloat() * Math.cos(12.0 * x).toFloat() }

    private fun glyph(c: Canvas, s: String, cx: Float, cy: Float, scale: Float, alpha: Float, rot: Float = 0f) {
        if (alpha <= 0f) return
        paint.style = Paint.Style.FILL
        paint.typeface = EmojiFont.get(ctx) ?: android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER; paint.textSize = ctx.dpf(32f)
        paint.color = theme.keyText; paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val fm = paint.fontMetrics
        c.save(); c.translate(cx, cy); c.rotate(rot); c.scale(scale, scale)
        c.drawText(s, 0f, -(fm.ascent + fm.descent) / 2, paint)
        c.restore(); paint.alpha = 255
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, color: Int, alpha: Float) {
        if (alpha <= 0f || r <= 0f) return
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = color; paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r, paint); paint.alpha = 255; paint.style = Paint.Style.FILL
    }

    override fun draw(c: Canvas, top: Float, w: Float) {
        layout(w)
        val now = SystemClock.uptimeMillis()
        val el = now - t0
        val answered = tRes != 0L
        val bm = if (res is EmojiKitchen.Result.Ok) bmp else null
        val ok = bm != null

        val enter = easeOut(el.toFloat() / enterMs)
        val pa = clamp01((el - windMs).toFloat() / approachMs)          // 0..1 approach progress
        val arrivedAt = t0 + windMs + approachMs
        val popAt = if (answered) max(tRes, arrivedAt) else Long.MAX_VALUE
        val u = if (answered && now >= popAt) clamp01((now - popAt).toFloat() / (if (ok) popMs else bounceMs)) else -1f
        val cx = w / 2f; val ey = ctx.dpf(40f); val spread = ctx.dpf(78f)
        val accent = theme.accent

        c.save(); c.translate(0f, top)
        // card (drops in)
        c.save()
        c.translate(0f, -(1f - enter) * ctx.dpf(10f)); c.scale(0.96f + 0.04f * enter, 0.96f + 0.04f * enter, card.centerX(), card.centerY())
        paint.style = Paint.Style.FILL; paint.color = theme.key; paint.alpha = (enter * 255).toInt()
        c.drawRoundRect(card, ctx.dpf(20f), ctx.dpf(20f), paint); paint.alpha = 255

        var gap: Float; var sc: Float; var al = 1f; var plusAl = 1f - pa; var rotA: Float; var rotB: Float; var dy: Float
        if (pa < 1f) {                                                     // wind-up, then dash in on a small arc
            val e = easeInBack(pa)
            gap = spread * (1f - e); sc = 1f + 0.12f * pa
            val lean = 16f * Math.sin(Math.PI * pa).toFloat()
            rotA = lean; rotB = -lean; dy = -Math.sin(Math.PI * pa).toFloat() * ctx.dpf(9f)
        } else {                                                           // waiting: overlapped, pulsing, wobbling
            val ph = (now - arrivedAt) / 1000f
            gap = ctx.dpf(5f) + ctx.dpf(3f) * Math.sin(ph * 14.0).toFloat()
            sc = 1.12f + 0.07f * Math.sin(ph * 11.0).toFloat()
            rotA = 7f * Math.sin(ph * 9.0).toFloat(); rotB = -rotA; dy = 0f
            plusAl = 0f
            if (u < 0f) {                                                  // ripple while the sticker loads
                val rp = ((now - arrivedAt) % 800L) / 800f
                ring(c, cx, ey, ctx.dpf(16f) + ctx.dpf(26f) * easeOut(rp), ctx.dpf(2f), accent, 0.35f * (1f - rp))
            }
        }
        if (u >= 0f && ok) {                                               // squash and vanish
            val ue = clamp01(u / 0.3f)
            gap = ctx.dpf(2f) * (1f - ue); sc = 1.19f + 0.45f * ue; al = 1f - ue; plusAl = 0f; rotA = 0f; rotB = 0f; dy = 0f
        }
        if (u >= 0f && !ok) {                                              // no mix: spring apart, tilt away
            val s = spring(u)
            gap = spread * 0.9f * s; sc = 1.19f - 0.19f * u; plusAl = clamp01((u - 0.2f) * 2f)
            rotA = -22f * s; rotB = 22f * s; dy = 0f
        }
        glyph(c, a, cx - gap, ey + dy, sc, al, rotA)
        glyph(c, b, cx + gap, ey + dy, sc, al, rotB)
        if (plusAl > 0f) {
            paint.typeface = null; paint.textAlign = Paint.Align.CENTER; paint.textSize = ctx.dpf(24f)
            paint.color = theme.hint; paint.alpha = (plusAl * 255).toInt().coerceIn(0, 255)
            c.drawText("+", cx, ey + ctx.dpf(8f), paint); paint.alpha = 255
        }

        if (ok && u >= 0f) {
            if (!popped) { popped = true; try { onPop?.invoke() } catch (e: Throwable) { } }
            val sx = sticker.centerX(); val sy = sticker.centerY()
            // glow + shock ring + sparkles
            paint.style = Paint.Style.FILL; paint.color = accent; paint.alpha = (0.22f * (1f - u) * 255).toInt()
            c.drawCircle(sx, sy, ctx.dpf(38f) * (0.6f + 0.6f * easeOut(u)), paint); paint.alpha = 255
            ring(c, sx, sy, ctx.dpf(14f) + ctx.dpf(46f) * easeOut(u), ctx.dpf(3f) * (1f - u) + ctx.dpf(0.8f), accent, 0.6f * (1f - u))
            val n = 10
            for (i in 0 until n) {
                val ang = Math.toRadians((i * 360.0 / n) + (if (i % 2 == 0) 0.0 else 14.0))
                val reach = ctx.dpf(30f) + ctx.dpf(24f + 10f * (i % 3)) * easeOut(u)
                val sz = ctx.dpf(if (i % 3 == 0) 4.2f else 3f) * (1f - u * 0.8f)
                paint.color = if (i % 2 == 0) accent else theme.keyText; paint.alpha = (clamp01(1.4f - 1.4f * u) * 255).toInt()
                c.drawCircle(sx + Math.cos(ang).toFloat() * reach, sy + Math.sin(ang).toFloat() * reach, sz, paint)
            }
            paint.alpha = 255
            // sticker: soft shadow, then spring with a settling wobble
            val us = clamp01((u - 0.1f) / 0.9f)
            val s = spring(us).coerceAtLeast(0f)
            val rot = 11f * Math.exp(-5.0 * us).toFloat() * Math.cos(16.0 * us).toFloat()
            paint.color = Color.BLACK; paint.alpha = (0.14f * clamp01(us * 3f) * 255).toInt()
            tmp.set(sx - ctx.dpf(22f) * s, sticker.bottom - ctx.dpf(3f), sx + ctx.dpf(22f) * s, sticker.bottom + ctx.dpf(3f))
            c.drawOval(tmp, paint); paint.alpha = 255
            bmpPaint.alpha = (clamp01(us * 4f) * 255).toInt()
            c.save(); c.translate(sx, sy); c.rotate(rot); c.scale(s, s); c.translate(-sx, -sy)
            c.drawBitmap(bm!!, null, sticker, bmpPaint)
            c.restore(); bmpPaint.alpha = 255
            // hint
            val ha = clamp01((us - 0.5f) * 2f)
            if (ha > 0f) {
                paint.typeface = null; paint.textAlign = Paint.Align.LEFT; paint.textSize = ctx.dpf(11f)
                paint.color = theme.hint; paint.alpha = (ha * 255).toInt()
                c.drawText("Tap to send", card.left + ctx.dpf(16f), ctx.dpf(38f), paint)
                c.drawText("Hold to save", card.left + ctx.dpf(16f), ctx.dpf(54f), paint); paint.alpha = 255
            }
            // favourite heart burst
            if (favT != 0L) {
                val ft = clamp01((now - favT).toFloat() / d(650))
                if (ft < 1f) {
                    val hs = ctx.dpf(22f) * (0.4f + 0.9f * spring(ft)) * (if (favOn) 1f else 0.8f)
                    val hx = sticker.right + ctx.dpf(2f); val hy = sticker.top + ctx.dpf(6f) - ctx.dpf(14f) * easeOut(ft)
                    Icons.draw(c, if (favOn) "heart" else "close", hx, hy, hs, if (favOn) Color.parseColor("#F28B82") else theme.hint)
                }
            }
        }
        if (answered && !ok && u >= 0f) {                                  // no mix for this pair
            val ma = clamp01((u - 0.35f) / 0.65f)
            val msg = if (res is EmojiKitchen.Result.NoMix) "Try a different emoji combo" else "Couldn't load this mix. Check your connection"
            paint.typeface = null; paint.textAlign = Paint.Align.CENTER; paint.textSize = ctx.dpf(14f)
            paint.color = theme.keyText; paint.alpha = (ma * 255).toInt()
            c.drawText(msg, cx, ctx.dpf(74f) + (1f - ma) * ctx.dpf(6f), paint); paint.alpha = 255
        }
        Icons.draw(c, "close", close.centerX(), close.centerY(), ctx.dpf(18f), theme.hint)
        c.restore()      // card transform
        c.restore()      // top translate

        val fav = favT != 0L && now - favT < d(650)
        if (el < enterMs || !answered || pa < 1f || now < popAt || u < 1f || fav) invalidate()
    }

    override fun tap(x: Float, y: Float, w: Float): Boolean {
        layout(w)
        if (RectF(close).apply { inset(-ctx.dpf(6f), -ctx.dpf(6f)) }.contains(x, y)) { onClose(); return true }
        if (res is EmojiKitchen.Result.Ok && bmp != null && RectF(sticker).apply { inset(-ctx.dpf(14f), -ctx.dpf(8f)) }.contains(x, y)) { onSend(a, b); return true }
        return true
    }

    override fun hold(x: Float, y: Float, w: Float): Boolean {
        layout(w)
        if (res is EmojiKitchen.Result.Ok && bmp != null && RectF(sticker).apply { inset(-ctx.dpf(14f), -ctx.dpf(8f)) }.contains(x, y)) { onFav(a, b); return true }
        return false
    }
}

// ======================================================================================================== banner as its own view
/** Hosts a [GridBanner] as a small view of its own, so only its area repaints while it animates (the emoji grid is left alone). */
class GridBannerView(ctx: Context, private val banner: GridBanner) : View(ctx) {
    override fun onDraw(c: Canvas) { banner.draw(c, 0f, width.toFloat()) }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { banner.tap(e.x, e.y, width.toFloat()); return true }
        override fun onLongPress(e: MotionEvent) {
            if (banner.hold(e.x, e.y, width.toFloat())) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e)
}

// ======================================================================================================== sticker grid (Browse)
/** Scrolling grid of Emoji Kitchen stickers, drawn lazily: only visible cells are loaded. Sections are separated by a divider line. */
class StickerGridView(ctx: Context) : View(ctx) {
    var onPick: ((StickerItem) -> Unit)? = null
    var onHold: ((StickerItem) -> Unit)? = null
    var theme: Theme = Theme.DARK
    var favKeys: Set<String> = emptySet()

    private var sections: List<StickerSection> = emptyList()
    private var message = ""
    private var cols = 4
    private var tops = FloatArray(0)
    private var total = 0f
    private var scrollYf = 0f
    private val scroller = OverScroller(ctx)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ready = Runnable { postInvalidateOnAnimation() }

    private fun pad() = context.dpf(8f)
    private fun cell() = (width - 2 * pad()) / cols
    private fun gap() = context.dpf(22f)

    fun setData(s: List<StickerSection>, msg: String) {
        sections = s; message = msg; scrollYf = 0f; relayout(); invalidate()
    }

    private fun relayout() {
        if (width == 0) return
        tops = FloatArray(sections.size)
        var y = pad()
        sections.forEachIndexed { i, sec ->
            tops[i] = y
            y += ((sec.items.size + cols - 1) / cols) * cell()
            if (i < sections.size - 1) y += gap()
        }
        total = y + pad()
        scrollYf = scrollYf.coerceIn(0f, max(0f, total - height))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYf = scroller.currY.toFloat().coerceIn(0f, max(0f, total - height))
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        if (sections.isEmpty()) {
            if (message.isNotEmpty()) {
                paint.color = theme.hint; paint.textSize = context.dpf(15f); paint.typeface = null
                val lines = message.split("\n")
                var y = height / 2f - (lines.size - 1) * context.dpf(11f)
                for (ln in lines) { c.drawText(ln, width / 2f, y, paint); y += context.dpf(22f) }
            }
            return
        }
        val cs = cell(); val ins = context.dpf(5f)
        val top = scrollYf; val bottom = scrollYf + height
        for (i in sections.indices) {
            val sec = sections[i]
            val rows = (sec.items.size + cols - 1) / cols
            val y0 = tops[i]
            val y1 = y0 + rows * cs
            if (i > 0) {                                                // divider above this section
                val ly = y0 - gap() / 2 - top
                paint.color = theme.hint; paint.alpha = 70; paint.strokeWidth = context.dpf(1f)
                c.drawLine(pad(), ly, width - pad(), ly, paint); paint.alpha = 255
            }
            if (y1 < top || y0 > bottom) continue
            val r0 = max(0, ((top - y0) / cs).toInt())
            val r1 = min(rows - 1, ((bottom - y0) / cs).toInt())
            for (r in r0..r1) for (k in 0 until cols) {
                val idx = r * cols + k
                if (idx >= sec.items.size) break
                val it = sec.items[idx]
                val x = pad() + k * cs; val y = y0 + r * cs - top
                val rect = RectF(x + ins, y + ins, x + cs - ins, y + cs - ins)
                val bmp = StickerLoader.get(context, it.a, it.b, rect.width().toInt(), ready)
                if (bmp != null) c.drawBitmap(bmp, null, rect, bmpPaint)
                else { paint.color = theme.key; paint.alpha = 80; c.drawRoundRect(rect, context.dpf(16f), context.dpf(16f), paint); paint.alpha = 255 }
                if (it.key in favKeys) Icons.draw(c, "heart", rect.right - context.dpf(8f), rect.top + context.dpf(8f), context.dpf(16f), theme.accent)
            }
        }
        if (total > height) {
            paint.color = theme.hint; paint.alpha = 90
            val bh = max(context.dpf(24f), height * height / total)
            val by = (height - bh) * (scrollYf / (total - height))
            c.drawRoundRect(width - context.dpf(5f), by, width - context.dpf(2f), by + bh, 4f, 4f, paint)
            paint.alpha = 255
        }
    }

    private fun itemAt(x: Float, y: Float): StickerItem? {
        val yy = y + scrollYf; val cs = cell()
        for (i in sections.indices) {
            val rows = (sections[i].items.size + cols - 1) / cols
            if (yy < tops[i] || yy >= tops[i] + rows * cs) continue
            val r = ((yy - tops[i]) / cs).toInt(); val k = ((x - pad()) / cs).toInt().coerceIn(0, cols - 1)
            return sections[i].items.getOrNull(r * cols + k)
        }
        return null
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollYf = (scrollYf + dy).coerceIn(0f, max(0f, total - height)); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollYf.toInt(), 0, (-vy).toInt(), 0, 0, 0, max(0, (total - height).toInt()))
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { itemAt(e.x, e.y)?.let { onPick?.invoke(it) }; return true }
        override fun onLongPress(e: MotionEvent) {
            val it = itemAt(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHold?.invoke(it)
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e)
}
