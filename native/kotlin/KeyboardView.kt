package com.openkeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Canvas-drawn keyboard: multi-touch, long-press alternates, swipe typing, cursor swipe on space. */
class KeyboardView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onText(text: String, letterLayer: Boolean)
        fun onSpecial(type: String)
        fun onDelete()
        fun onCursorMove(steps: Int)
        fun onSwipe(path: String, shifted: Boolean)
        fun onLayerChange(name: String)
    }

    private class Cell(val key: KeyDef, val rect: RectF)

    private class Ptr(val id: Int, val cell: Cell?, val dx: Float, val dy: Float) {
        var x = dx; var y = dy
        var longFired = false
        var alts: List<String>? = null
        var altSel = 0; var altLeft = 0f; var altW = 0f
        var moved = false
        var cursorMode = false
        var lastCx = dx
        var swiping = false
        val path = StringBuilder()
        val trail = Path().also { it.moveTo(dx, dy) }
        var lp: Runnable? = null
        var rep: Runnable? = null
    }

    var listener: Listener? = null
    var layout: LayoutDef? = null
    var layerName = "base"
    var shiftState = 0           // 0 off, 1 once, 2 caps lock
    var autoShifted = false
    var enterLabel = "⏎"
    var theme: Theme = Theme.DARK
    var heightScale = 100; var fontScale = 100; var radiusDp = 8; var gapDp = 4
    var showHints = true; var popupPreview = true; var swipeOn = true
    var hapticOn = true; var hapticMs = 12; var soundOn = false; var longPressMs = 350
    var numberRow = false; var oneHanded = "off"

    private val handler = Handler(Looper.getMainLooper())
    private val cells = ArrayList<Cell>()
    private var rows: List<List<KeyDef>> = emptyList()
    private val ptrs = SparseArray<Ptr>()
    private var lastShiftTap = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    @Suppress("DEPRECATION")
    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun rowH() = context.dpf(52f) * heightScale / 100f
    private fun pad() = context.dpf(6f)
    private fun hasNumRow() = numberRow && layout?.id != "numeric"
    private fun targetRows() = (layout?.layers?.get("base")?.size ?: 4) + (if (hasNumRow()) 1 else 0)

    fun setLayout(l: LayoutDef) {
        layout = l; layerName = "base"; shiftState = 0; autoShifted = false
        refresh()
    }

    fun refresh() { requestLayout(); rebuild() }

    fun setShift(state: Int, auto: Boolean = false) {
        if (shiftState != state) { shiftState = state; autoShifted = auto; rebuild() }
    }

    fun showLayer(name: String) { layerName = name; shiftState = 0; autoShifted = false; rebuild() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (targetRows() * rowH() + pad()).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }

    private fun buildRows(): List<List<KeyDef>> {
        val l = layout ?: return emptyList()
        val hasShiftLayer = l.layers.containsKey("shift")
        val useShift = layerName == "base" && shiftState > 0
        var r: List<List<KeyDef>> =
            if (useShift && hasShiftLayer) l.layers["shift"]!! else (l.layers[layerName] ?: l.layers["base"]!!)
        if (useShift && !hasShiftLayer) r = r.map { row ->
            row.map { k ->
                if (k.type == "char") k.copy(label = k.label.uppercase(), output = k.output.uppercase(), hints = k.hints.map { it.uppercase() }) else k
            }
        }
        if (hasNumRow()) {
            if (layerName == "base") r = listOf(l.numberRow ?: LayoutParser.row("1 2 3 4 5 6 7 8 9 0")) + r
            r = r.map { row ->
                row.map { k ->
                    if (k.type == "char" && k.hints.any { it.length == 1 && it[0] in '0'..'9' })
                        k.copy(hints = k.hints.filter { !(it.length == 1 && it[0] in '0'..'9') }) else k
                }
            }
        }
        return r
    }

    private fun rebuild() {
        cells.clear()
        rows = buildRows()
        if (rows.isEmpty() || width == 0 || height == 0) { invalidate(); return }
        val rh = (height - pad()) / rows.size
        val g = context.dpf(gapDp.toFloat())
        val availW = if (oneHanded == "off") width.toFloat() else width * 0.8f
        val left = if (oneHanded == "right") width - availW else 0f
        rows.forEachIndexed { ri, row ->
            val unit = availW / max(0.1f, row.sumOf { it.weight.toDouble() }.toFloat())
            var x = left
            val top = pad() / 2 + ri * rh
            for (k in row) {
                val w = unit * k.weight
                if (k.type != "pad") cells.add(Cell(k, RectF(x + g / 2, top + g / 2, x + w - g / 2, top + rh - g / 2)))
                x += w
            }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ drawing
    private fun centered(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, maxW: Float) {
        txt.color = color; txt.textSize = size; txt.textAlign = Paint.Align.CENTER
        val w = txt.measureText(s)
        if (w > maxW && w > 0) txt.textSize = size * maxW / w
        val fm = txt.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, txt)
    }

    override fun onDraw(c: Canvas) {
        val r = context.dpf(radiusDp.toFloat())
        val rh = if (rows.isEmpty()) 0f else (height - pad()) / rows.size
        val fs = rh * 0.40f * fontScale / 100f
        for (cell in cells) {
            val k = cell.key
            var pressed = false
            for (i in 0 until ptrs.size()) if (ptrs.valueAt(i).cell === cell) pressed = true
            val active = k.type == "enter" || (k.type == "shift" && shiftState > 0)
            fill.color = when {
                active -> theme.accent
                pressed -> theme.pressed
                k.type == "char" || k.type == "space" -> theme.key
                else -> theme.special
            }
            c.drawRoundRect(cell.rect, r, r, fill)
            val fg = if (active) theme.accentText else theme.keyText
            val rc = cell.rect
            when (k.type) {
                "space" -> centered(c, layout?.name ?: "", rc.centerX(), rc.centerY(), fs * 0.6f, theme.hint, rc.width() * 0.8f)
                "enter" -> centered(c, enterLabel, rc.centerX(), rc.centerY(), fs * 0.75f, fg, rc.width() * 0.8f)
                "shift" -> centered(c, if (shiftState == 2) "⇪" else "⇧", rc.centerX(), rc.centerY(), fs * 1.1f, fg, rc.width() * 0.8f)
                "char" -> {
                    centered(c, displayText(k.label), rc.centerX(), rc.centerY(), fs, fg, rc.width() * 0.82f)
                    if (showHints && k.hints.isNotEmpty() && k.hints[0].codePointCount(0, k.hints[0].length) <= 2) {
                        txt.color = theme.hint; txt.textSize = rh * 0.19f; txt.textAlign = Paint.Align.RIGHT
                        c.drawText(displayText(k.hints[0]), rc.right - rh * 0.08f, rc.top + rh * 0.24f, txt)
                    }
                }
                else -> centered(c, k.label, rc.centerX(), rc.centerY(), fs * 0.72f, fg, rc.width() * 0.8f)
            }
        }
        for (i in 0 until ptrs.size()) {
            val p = ptrs.valueAt(i)
            val cell = p.cell ?: continue
            if (p.swiping) {
                stroke.color = theme.accent; stroke.alpha = 170; stroke.strokeWidth = context.dpf(5f)
                c.drawPath(p.trail, stroke)
            } else if (p.alts != null) drawAlts(c, p, cell, rh)
            else if (popupPreview && cell.key.type == "char" && !p.cursorMode) drawPreview(c, cell, rh)
        }
    }

    private fun drawPreview(c: Canvas, cell: Cell, rh: Float) {
        val w = cell.rect.width() * 1.4f; val h = rh * 1.25f
        val l = (cell.rect.centerX() - w / 2).coerceIn(2f, max(2f, width - w - 2f))
        val t = max(cell.rect.top - h - context.dpf(4f), -context.dpf(40f))
        val rect = RectF(l, t, l + w, t + h)
        fill.color = theme.popup; c.drawRoundRect(rect, context.dpf(10f), context.dpf(10f), fill)
        centered(c, displayText(cell.key.label), rect.centerX(), rect.centerY(), rh * 0.62f, theme.keyText, w * 0.8f)
    }

    private fun drawAlts(c: Canvas, p: Ptr, cell: Cell, rh: Float) {
        val alts = p.alts ?: return
        val h = rh * 1.1f
        val t = max(cell.rect.top - h - context.dpf(6f), -context.dpf(40f))
        val bg = RectF(p.altLeft - 3f, t, p.altLeft + p.altW * alts.size + 3f, t + h)
        fill.color = theme.popup; c.drawRoundRect(bg, context.dpf(10f), context.dpf(10f), fill)
        alts.forEachIndexed { i, a ->
            val cr = RectF(p.altLeft + i * p.altW, t + 3f, p.altLeft + (i + 1) * p.altW, t + h - 3f)
            val sel = i == p.altSel
            if (sel) { fill.color = theme.accent; c.drawRoundRect(cr, context.dpf(8f), context.dpf(8f), fill) }
            centered(c, displayText(a), cr.centerX(), cr.centerY(), rh * 0.46f, if (sel) theme.accentText else theme.keyText, p.altW * 0.85f)
        }
    }

    // ------------------------------------------------------------------ touch
    private fun hit(x: Float, y: Float): Cell? {
        var best: Cell? = null; var bd = Float.MAX_VALUE
        for (c in cells) {
            val r = c.rect
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = c }
        }
        val lim = context.dpf(28f)
        return if (bd < lim * lim) best else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until ptrs.size()) { ptrs.valueAt(i).lp?.let { handler.removeCallbacks(it) }; ptrs.valueAt(i).rep?.let { handler.removeCallbacks(it) } }
                ptrs.clear(); invalidate()
            }
        }
        return true
    }

    private fun feedback() {
        if (hapticOn) {
            if (hapticMs > 0 && vib != null) {
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(hapticMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                else vib.vibrate(hapticMs.toLong())
            } else performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (soundOn) (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun down(id: Int, x: Float, y: Float) {
        val cell = hit(x, y)
        val p = Ptr(id, cell, x, y)
        ptrs.put(id, p)
        if (cell == null) return
        val k = cell.key
        feedback()
        if (k.type == "char" && k.output.isNotEmpty()) p.path.append(k.output.lowercase())
        if (k.type == "del") {
            listener?.onDelete()
            val rep = object : Runnable {
                var n = 0
                override fun run() {
                    if (ptrs.get(id) !== p) return
                    listener?.onDelete(); n++
                    handler.postDelayed(this, if (n > 12) 30L else 55L)
                }
            }
            p.rep = rep; handler.postDelayed(rep, 400)
        } else if ((k.type == "char" && k.hints.isNotEmpty()) || k.type == "lang") {
            val r = Runnable {
                if (ptrs.get(id) !== p || p.cursorMode || p.swiping) return@Runnable
                p.longFired = true
                if (k.type == "lang") listener?.onSpecial("picker") else openAlts(p, cell)
                invalidate()
            }
            p.lp = r; handler.postDelayed(r, longPressMs.toLong())
        }
        invalidate()
    }

    private fun openAlts(p: Ptr, cell: Cell) {
        val alts = cell.key.hints.take(9)
        val cw = min(max(cell.rect.width() * 0.95f, context.dpf(38f)), (width - 8f) / alts.size)
        p.altW = cw
        p.altLeft = cell.rect.left.coerceIn(2f, max(2f, width - cw * alts.size - 2f))
        p.alts = alts
        p.altSel = ((p.x - p.altLeft) / cw).toInt().coerceIn(0, alts.size - 1)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        p.x = x; p.y = y
        val alts = p.alts
        if (alts != null) { p.altSel = ((x - p.altLeft) / p.altW).toInt().coerceIn(0, alts.size - 1); invalidate(); return }
        val k = p.cell?.key ?: return
        val slop = context.dpf(14f)
        if (k.type == "space") {
            if (abs(x - p.dx) > slop * 1.5f || p.cursorMode) {
                p.cursorMode = true; p.lp?.let { handler.removeCallbacks(it) }
                val step = context.dpf(14f)
                val n = ((x - p.lastCx) / step).toInt()
                if (n != 0) { listener?.onCursorMove(n); p.lastCx += n * step }
            }
            return
        }
        if (abs(x - p.dx) > slop || abs(y - p.dy) > slop) {
            if (!p.moved) { p.moved = true; p.lp?.let { handler.removeCallbacks(it) } }
            val swipeable = swipeOn && layout?.swipe == true && layerName == "base" && k.type == "char"
            if (swipeable) {
                val c = hit(x, y)
                if (c != null && c.key.type == "char" && c.key.output.length == 1 && c.key.output[0].isLetter()) {
                    val ch = c.key.output.lowercase()
                    if (p.path.isEmpty() || p.path.last().toString() != ch) p.path.append(ch)
                }
                if (p.path.length >= 3) p.swiping = true
                if (p.swiping) { p.trail.lineTo(x, y); invalidate() }
            }
        }
        if (p.swiping) return
        invalidate()
    }

    private fun afterChar() { if (shiftState == 1) { shiftState = 0; autoShifted = false; rebuild() } }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        p.lp?.let { handler.removeCallbacks(it) }; p.rep?.let { handler.removeCallbacks(it) }
        try {
            val alts = p.alts
            if (alts != null) {
                alts.getOrNull(p.altSel)?.let { afterChar(); listener?.onText(it, layerName == "base") }
                return
            }
            if (p.swiping) { val s = shiftState > 0; afterChar(); listener?.onSwipe(p.path.toString(), s); return }
            if (p.cursorMode) return
            val cell = p.cell ?: return
            if (p.longFired) return
            var target = cell
            if (p.moved && cell.key.type == "char") hit(x, y)?.let { target = it }
            val k = target.key
            when (k.type) {
                "char" -> if (k.output.isNotEmpty()) { val letter = layerName == "base"; afterChar(); listener?.onText(k.output, letter) }
                "shift" -> toggleShift()
                "layer" -> { showLayer(k.param ?: "base"); listener?.onLayerChange(layerName) }
                "del" -> {}
                else -> listener?.onSpecial(k.type)
            }
        } finally { invalidate() }
    }

    private fun toggleShift() {
        if (layerName != "base") return
        val now = SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == 2 -> 0
            shiftState == 1 && now - lastShiftTap < 350 -> 2
            shiftState == 1 -> 0
            else -> 1
        }
        autoShifted = false; lastShiftTap = now
        rebuild()
    }
}
