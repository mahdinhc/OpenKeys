package com.openkeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

class EmojiSection(val title: String, val items: List<String>)

/** Fast scrolling emoji / emoticon grid with section headers and long-press skin-tone picker. */
class EmojiGridView(ctx: Context) : View(ctx) {
    var onPick: ((String) -> Unit)? = null
    var onSection: ((Int) -> Unit)? = null
    var toneBase: ((String) -> Boolean)? = null          // does this emoji have skin tones?
    var displayOf: ((String) -> String)? = null           // preferred (toned) form
    var onTone: ((String, String) -> Unit)? = null        // base, chosen variant
    var theme: Theme = Theme.DARK

    private var sections: List<EmojiSection> = emptyList()
    private var cols = 8
    private var textFrac = 0.62f
    private var cellHFixed = 0f
    private var tops = FloatArray(0)
    private var total = 0f
    private var scrollYf = 0f
    private var lastSection = -1
    private val scroller = OverScroller(ctx)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()

    private var popup: List<String>? = null
    private var popupBase = ""
    private var popupRect = RectF()
    private var popupSel = 0
    private var popupCw = 0f

    private fun cellW() = width / cols.toFloat()
    private fun cellH() = if (cellHFixed > 0) cellHFixed else cellW() * max(0.92f, textFrac * 1.45f)
    private fun headerH() = context.dpf(34f)

    fun setData(s: List<EmojiSection>, columns: Int, textFraction: Float, fixedCellH: Float = 0f) {
        sections = s; cols = columns; textFrac = textFraction; cellHFixed = fixedCellH
        scrollYf = 0f; lastSection = -1; relayout(); invalidate()
    }

    private fun relayout() {
        if (width == 0) return
        tops = FloatArray(sections.size)
        var y = 0f
        sections.forEachIndexed { i, sec ->
            tops[i] = y
            if (sec.title.isNotEmpty()) y += headerH()
            y += ((sec.items.size + cols - 1) / cols) * cellH()
        }
        total = y
        scrollYf = scrollYf.coerceIn(0f, max(0f, total - height))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    fun scrollToSection(i: Int) {
        if (i !in sections.indices) return
        scroller.forceFinished(true)
        val target = min(tops[i], max(0f, total - height))
        scroller.startScroll(0, scrollYf.toInt(), 0, (target - scrollYf).toInt(), 250)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYf = scroller.currY.toFloat().coerceIn(0f, max(0f, total - height))
            reportSection()
            postInvalidateOnAnimation()
        }
    }

    private fun reportSection() {
        var cur = 0
        for (i in sections.indices) if (tops[i] <= scrollYf + headerH()) cur = i
        if (cur != lastSection) { lastSection = cur; onSection?.invoke(cur) }
    }

    override fun onDraw(c: Canvas) {
        if (sections.isEmpty()) return
        val cw = cellW(); val ch = cellH()
        val top = scrollYf; val bottom = scrollYf + height
        for (i in sections.indices) {
            val sec = sections[i]
            val rows = (sec.items.size + cols - 1) / cols
            var y = tops[i]
            val end = y + (if (sec.title.isNotEmpty()) headerH() else 0f) + rows * ch
            if (end < top || y > bottom) continue
            if (sec.title.isNotEmpty()) {
                paint.color = theme.hint; paint.textSize = context.dpf(14f); paint.textAlign = Paint.Align.LEFT
                c.drawText(sec.title, context.dpf(12f), y - top + headerH() * 0.68f, paint)
                y += headerH()
            }
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (cellHFixed > 0) context.dpf(15f) else cw * textFrac
            paint.color = theme.keyText
            val fm = paint.fontMetrics
            val r0 = max(0, ((top - y) / ch).toInt())
            val r1 = min(rows - 1, ((bottom - y) / ch).toInt())
            for (r in r0..r1) for (k in 0 until cols) {
                val idx = r * cols + k
                if (idx >= sec.items.size) break
                val e = sec.items[idx]
                val cx = k * cw + cw / 2
                val cy = y + r * ch + ch / 2 - top
                val shown = displayOf?.invoke(e) ?: e
                c.drawText(shown, cx, cy - (fm.ascent + fm.descent) / 2, paint)
                if (toneBase?.invoke(e) == true) {
                    val s = context.dpf(5f); val bx = (k + 1) * cw - 3f; val by = y + (r + 1) * ch - top - 3f
                    tri.reset(); tri.moveTo(bx, by - s); tri.lineTo(bx, by); tri.lineTo(bx - s, by); tri.close()
                    fill.color = theme.hint; c.drawPath(tri, fill)
                }
            }
        }
        // scroll indicator
        if (total > height) {
            fill.color = theme.hint; fill.alpha = 90
            val bh = max(context.dpf(24f), height * height / total)
            val by = (height - bh) * (scrollYf / (total - height))
            c.drawRoundRect(width - context.dpf(5f), by, width - context.dpf(2f), by + bh, 4f, 4f, fill)
            fill.alpha = 255
        }
        popup?.let { drawPopup(c, it) }
    }

    private fun drawPopup(c: Canvas, items: List<String>) {
        fill.color = theme.popup; c.drawRoundRect(popupRect, context.dpf(12f), context.dpf(12f), fill)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = popupCw * 0.62f
        val fm = paint.fontMetrics
        items.forEachIndexed { i, e ->
            val cr = RectF(popupRect.left + i * popupCw, popupRect.top, popupRect.left + (i + 1) * popupCw, popupRect.bottom)
            if (i == popupSel) { fill.color = theme.accent; fill.alpha = 120; c.drawRoundRect(cr, 12f, 12f, fill); fill.alpha = 255 }
            c.drawText(e, cr.centerX(), cr.centerY() - (fm.ascent + fm.descent) / 2, paint)
        }
    }

    // ---------------------------------------------------------------- touch
    private fun itemAt(x: Float, y: Float): Triple<String, Float, Float>? {
        val yy = y + scrollYf
        val cw = cellW(); val ch = cellH()
        for (i in sections.indices) {
            val sec = sections[i]
            var s = tops[i] + (if (sec.title.isNotEmpty()) headerH() else 0f)
            val rows = (sec.items.size + cols - 1) / cols
            if (yy < s || yy >= s + rows * ch) continue
            val r = ((yy - s) / ch).toInt(); val k = (x / cw).toInt().coerceIn(0, cols - 1)
            val idx = r * cols + k
            if (idx >= sec.items.size) return null
            return Triple(sec.items[idx], k * cw + cw / 2, s + r * ch - scrollYf)
        }
        return null
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollYf = (scrollYf + dy).coerceIn(0f, max(0f, total - height)); reportSection(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollYf.toInt(), 0, (-vy).toInt(), 0, 0, 0, max(0, (total - height).toInt()))
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            itemAt(e.x, e.y)?.let { onPick?.invoke(displayOf?.invoke(it.first) ?: it.first) }; return true
        }
        override fun onLongPress(e: MotionEvent) {
            val it = itemAt(e.x, e.y) ?: return
            if (toneBase?.invoke(it.first) != true) return
            val v = EmojiData.variants(it.first)
            if (v.size < 2) return
            popup = v; popupBase = it.first
            popupCw = min(cellW() * 1.1f, (width - 8f) / v.size)
            val w = popupCw * v.size; val h = cellH() * 1.1f
            val l = (it.second - w / 2).coerceIn(4f, max(4f, width - w - 4f))
            val t = if (it.third - h - 4f >= 0) it.third - h - 4f else it.third + cellH() + 4f
            popupRect = RectF(l, t, l + w, t + h)
            popupSel = ((e.x - l) / popupCw).toInt().coerceIn(0, v.size - 1)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pv = popup
        if (pv != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> { popupSel = ((e.x - popupRect.left) / popupCw).toInt().coerceIn(0, pv.size - 1); invalidate() }
                MotionEvent.ACTION_UP -> {
                    val chosen = pv[popupSel]
                    onTone?.invoke(popupBase, chosen); popup = null; invalidate()
                    onPick?.invoke(chosen)
                }
                MotionEvent.ACTION_CANCEL -> { popup = null; invalidate() }
            }
            return true
        }
        return gd.onTouchEvent(e)
    }
}
