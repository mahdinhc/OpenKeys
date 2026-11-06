package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
