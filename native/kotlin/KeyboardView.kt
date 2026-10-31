package com.openkeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Canvas-drawn keyboard: multi-touch, long-press alternates, swipe typing, space = cursor / language. */
class KeyboardView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onText(text: String, letterLayer: Boolean)
        fun onSpecial(type: String)
        fun onDelete()
        fun onCursorMove(steps: Int)
        fun onSwipe(input: SwipeInput, shifted: Boolean)
        fun onLayerChange(name: String)
        fun onSelectLayout(index: Int)
        fun onKey(keyCode: Int, meta: Int)
    }

    private class Cell(val key: KeyDef, val rect: RectF)

    private class Ptr(val id: Int, val cell: Cell?, val dx: Float, val dy: Float) {
        var x = dx; var y = dy
        var longFired = false
        var alts: List<String>? = null
        var langMode = false
        var altSel = 0; var altLeft = 0f; var altW = 0f
        var altTop = 0f; var altH = 0f
        var moved = false
        var cursorMode = false
        var lastCx = dx
        var swiping = false
        val path = StringBuilder()
        val pts = ArrayList<Float>()
        var trailLen = 0f
        val trail = Path().also { it.moveTo(dx, dy) }
        var lp: Runnable? = null
        var rep: Runnable? = null
    }

    var listener: Listener? = null
    var layout: LayoutDef? = null
    var layerName = "base"
    var shiftState = 0           // 0 off, 1 once, 2 caps lock
    var autoShifted = false
    var enterIcon = "return"
    var langNames: List<String> = emptyList()
    var langIndex = 0
    var theme: Theme = Theme.DARK
    var heightScale = 100; var fontScale = 100; var radiusDp = 12; var gapDp = 4
    var showHints = true; var popupPreview = true; var swipeOn = true
    var hapticOn = true; var hapticMs = 12; var soundOn = false; var longPressMs = 350
    var numberRow = true; var oneHanded = "off"
    var numAlts: Map<String, List<String>> = emptyMap()   // digit -> long-press alternatives (customisable in settings)
    var typeface: Typeface? = null
        set(v) { field = v; txt.typeface = v ?: Typeface.DEFAULT; invalidate() }

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

    init { Icons.load(ctx) }

    private fun rowH() = context.dpf(56f) * heightScale / 100f * (layout?.rowScale ?: 1f)
    private fun pad() = context.dpf(6f)
    private fun withNumAlts(k: KeyDef): KeyDef {
        if (k.type != "char" || k.output.length != 1) return k
        val ch = k.output[0]
        val d = when (ch) { in '0'..'9' -> ch; in '\u09E6'..'\u09EF' -> '0' + (ch - '\u09E6'); else -> return k }
        val extra = numAlts[d.toString()] ?: return k
        if (extra.isEmpty()) return k
        return k.copy(hints = (k.hints + extra).distinct().take(9))
    }

    private fun hasNumRow() = numberRow && layout?.id != "numeric" && layout?.noNumberRow != true
    private fun targetRows() = (layout?.layers?.get("base")?.size ?: 4) + (if (hasNumRow() && layout?.layers?.get("base")?.firstOrNull()?.any { it.type == "stack" } != true) 1 else 0)

    fun applyLayout(l: LayoutDef) {
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
        val numpadLike = l.layers["base"]?.firstOrNull()?.any { it.type == "stack" } == true
        if (hasNumRow() && !numpadLike) {
            if (layerName == "base") r = listOf((l.numberRow ?: LayoutParser.row("1 2 3 4 5 6 7 8 9 0")).map { withNumAlts(it) }) + r
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
                if (k.type != "pad") cells.add(Cell(k, RectF(x + g / 2, top + g / 2, x + w - g / 2, top + rh * k.span - g / 2)))
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

    private fun keyIcon(k: KeyDef): String? = when (k.type) {
        "shift" -> when (shiftState) { 0 -> "shift"; 1 -> "shift_on"; else -> "caps" }
        "del" -> "backspace"
        "emoji" -> "emoji"
        "mic" -> "mic"
        "lang" -> "translate"
        "enter" -> enterIcon
        else -> null
    }

    private fun isPill(k: KeyDef) = k.type == "enter" || (k.type == "layer" && (k.param == "base" || k.param == "letters"))

    override fun onDraw(c: Canvas) {
        val r = context.dpf(radiusDp.toFloat())
        val rh = if (rows.isEmpty()) 0f else (height - pad()) / rows.size
        val fs = rh * 0.40f * fontScale / 100f
        val sh = context.dpf(1.5f)
        for (cell in cells) {
            val k = cell.key
            val rc = cell.rect
            var pressed = false
            for (i in 0 until ptrs.size()) if (ptrs.valueAt(i).cell === cell) pressed = true
            val rad = if (isPill(k)) min(rc.width(), rc.height()) / 2 else r
            fill.color = 0x38000000
            c.drawRoundRect(rc.left, rc.top + sh, rc.right, rc.bottom + sh, rad, rad, fill)
            fill.color = when {
                k.type == "enter" -> theme.accent
                k.type == "mod" && mods.getOrElse(modIdx(k.param)) { 0 } > 0 -> theme.accent
                pressed -> theme.pressed
                k.type == "char" || k.type == "space" -> theme.key
                else -> theme.special
            }
            c.drawRoundRect(rc, rad, rad, fill)
            val fg = if (k.type == "enter") theme.accentText else theme.keyText
            val icon = keyIcon(k)
            when {
                k.type == "stack" -> {
                    val n = max(1, k.hints.size)
                    k.hints.forEachIndexed { i, h ->
                        centered(c, h, rc.centerX(), rc.top + (i + 0.5f) * rc.height() / n, fs * 0.8f, theme.hint, rc.width() * 0.7f)
                    }
                }
                icon != null -> {
                    val col = if (k.type == "shift" && shiftState > 0) theme.accent else fg
                    Icons.draw(c, icon, rc.centerX(), rc.centerY(), min(rc.height() * 0.46f, rc.width() * 0.7f), col)
                }
                k.type == "space" ->
                    if (k.weight < 2f) Icons.draw(c, "space_bar", rc.centerX(), rc.centerY(), rh * 0.42f, theme.hint)
                    else centered(c, layout?.name ?: "", rc.centerX(), rc.centerY(), fs * 0.62f, theme.hint, rc.width() * 0.8f)
                k.type == "layer" && k.label == "1234" -> {
                    val s2 = fs * 0.62f
                    centered(c, "1 2", rc.centerX(), rc.centerY() - s2 * 0.62f, s2, theme.keyText, rc.width() * 0.6f)
                    centered(c, "3 4", rc.centerX(), rc.centerY() + s2 * 0.62f, s2, theme.keyText, rc.width() * 0.6f)
                }
                k.type == "mod" || k.type == "key" || k.type == "act" ->
                    centered(c, k.label, rc.centerX(), rc.centerY(), fs * 0.62f, if (k.type == "mod" && mods.getOrElse(modIdx(k.param)) { 0 } > 0) theme.accentText else theme.keyText, rc.width() * 0.88f)
                k.type == "layer" -> centered(c, k.label, rc.centerX(), rc.centerY(), fs * 0.7f, theme.hint.let { if (isPill(k)) theme.hint else theme.keyText }, rc.width() * 0.8f)
                k.type == "char" -> {
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
            } else if (p.alts != null) { if (p.langMode) drawLangPopup(c, p) else drawAlts(c, p, cell, rh) }
            else if (popupPreview && cell.key.type == "char" && !p.cursorMode) drawPreview(c, cell, rh)
        }
    }

    private fun drawPreview(c: Canvas, cell: Cell, rh: Float) {
        val w = cell.rect.width() * 1.4f; val h = rh * 1.25f
        val l = (cell.rect.centerX() - w / 2).coerceIn(2f, max(2f, width - w - 2f))
        val t = max(cell.rect.top - h - context.dpf(4f), -context.dpf(44f))
        val rect = RectF(l, t, l + w, t + h)
        fill.color = theme.popup; c.drawRoundRect(rect, context.dpf(12f), context.dpf(12f), fill)
        centered(c, displayText(cell.key.label), rect.centerX(), rect.centerY(), rh * 0.62f, theme.keyText, w * 0.8f)
    }

    private fun drawAlts(c: Canvas, p: Ptr, cell: Cell, rh: Float) {
        val alts = p.alts ?: return
        val h = rh * 1.1f
        val t = max(cell.rect.top - h - context.dpf(6f), -context.dpf(44f))
        val bg = RectF(p.altLeft - 3f, t, p.altLeft + p.altW * alts.size + 3f, t + h)
        fill.color = theme.popup; c.drawRoundRect(bg, context.dpf(12f), context.dpf(12f), fill)
        alts.forEachIndexed { i, a ->
            val cr = RectF(p.altLeft + i * p.altW, t + 3f, p.altLeft + (i + 1) * p.altW, t + h - 3f)
            val sel = i == p.altSel
            if (sel) { fill.color = theme.accent; c.drawRoundRect(cr, context.dpf(10f), context.dpf(10f), fill) }
            centered(c, displayText(a), cr.centerX(), cr.centerY(), rh * (if (p.langMode) 0.34f else 0.46f), if (sel) theme.accentText else theme.keyText, p.altW * 0.88f)
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
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                for (h in 0 until e.historySize) move(id, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                move(id, e.getX(i), e.getY(i))
            }
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
        p.pts.add(x); p.pts.add(y)
        if (k.type == "key") {
            fireSpec(k.param ?: "")
            if (repeatable(k.param ?: "")) {
                val rep = object : Runnable {
                    var n = 0
                    override fun run() { if (ptrs.get(id) !== p) return; fireSpec(k.param ?: ""); n++; handler.postDelayed(this, if (n > 12) 30L else 55L) }
                }
                p.rep = rep; handler.postDelayed(rep, 400)
            }
        } else if (k.type == "del") {
            if (anyMod()) fireCode(KeyEvent.KEYCODE_DEL) else listener?.onDelete()
            val rep = object : Runnable {
                var n = 0
                override fun run() {
                    if (ptrs.get(id) !== p) return
                    listener?.onDelete(); n++
                    handler.postDelayed(this, if (n > 12) 30L else 55L)
                }
            }
            p.rep = rep; handler.postDelayed(rep, 400)
        } else if ((k.type == "char" && k.hints.isNotEmpty()) || k.type == "lang" || k.type == "space") {
            val r = Runnable {
                if (ptrs.get(id) !== p || p.cursorMode || p.swiping) return@Runnable
                p.longFired = true
                when (k.type) {
                    "lang" -> listener?.onSpecial("picker")
                    "space" -> openLangPopup(p, cell)
                    else -> openAlts(p, cell)
                }
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

    /** Hold space: a neat vertical stack above the space bar. Slide up/down to choose; release to switch. */
    private fun openLangPopup(p: Ptr, cell: Cell) {
        val n = langNames.size
        if (n < 2) { listener?.onSpecial("picker"); return }
        val avail = cell.rect.top + context.dpf(44f) - context.dpf(10f)
        val lh = min(rowH() * 0.8f, avail / n).coerceAtLeast(context.dpf(22f))
        txt.textSize = lh * 0.46f
        var w = 0f; for (nm in langNames) w = max(w, txt.measureText(displayText(nm)))
        w = (w + context.dpf(44f)).coerceIn(context.dpf(120f), width - 8f)
        p.altW = w; p.altH = lh
        p.altLeft = (cell.rect.centerX() - w / 2).coerceIn(4f, max(4f, width - w - 4f))
        p.altTop = max(cell.rect.top - lh * n - context.dpf(8f), -context.dpf(44f))
        p.alts = langNames; p.langMode = true
        p.altSel = (langIndex + 1) % n
    }

    private fun drawLangPopup(c: Canvas, p: Ptr) {
        val alts = p.alts ?: return
        val pad = context.dpf(3f); val rr = context.dpf(12f)
        val bg = RectF(p.altLeft, p.altTop - pad, p.altLeft + p.altW, p.altTop + p.altH * alts.size + pad)
        fill.color = theme.popup; c.drawRoundRect(bg, rr, rr, fill)
        alts.forEachIndexed { i, a ->
            val cr = RectF(p.altLeft + pad, p.altTop + i * p.altH, p.altLeft + p.altW - pad, p.altTop + (i + 1) * p.altH)
            val sel = i == p.altSel
            if (sel) { fill.color = theme.accent; c.drawRoundRect(cr, rr * 0.8f, rr * 0.8f, fill) }
            val col = if (sel) theme.accentText else theme.keyText
            centered(c, displayText(a), cr.centerX(), cr.centerY(), p.altH * 0.46f, col, cr.width() - context.dpf(34f))
            if (i == langIndex) { fill.color = col; c.drawCircle(cr.left + context.dpf(12f), cr.centerY(), context.dpf(3f), fill) }
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        p.x = x; p.y = y
        val alts = p.alts
        if (alts != null) {
            p.altSel = (if (p.langMode) (y - p.altTop) / p.altH else (x - p.altLeft) / p.altW).toInt().coerceIn(0, alts.size - 1)
            invalidate(); return
        }
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
        }
        val swipeable = p.moved && swipeOn && layout?.swipe == true && layerName == "base" && k.type == "char"
        if (swipeable) {
            // Sample the whole finger path (not just the key under each event): fast swipes skip keys between events.
            val n = p.pts.size
            val lx = p.pts[n - 2]; val ly = p.pts[n - 1]
            val dist = hypot(x - lx, y - ly)
            val step = max(4f, context.dpf(6f))
            val steps = max(1, (dist / step).toInt())
            for (i in 1..steps) {
                val sx = lx + (x - lx) * i / steps; val sy = ly + (y - ly) * i / steps
                val c = hitLetter(sx, sy)
                if (c != null) { val ch = c.key.output.lowercase(); if (p.path.isEmpty() || p.path.last().toString() != ch) p.path.append(ch) }
            }
            p.pts.add(x); p.pts.add(y)
            p.trailLen += dist
            p.trail.lineTo(x, y)
            if (!p.swiping && p.path.length >= 3 && p.trailLen > keyWidth() * 1.8f) p.swiping = true
            if (p.swiping) invalidate()
        }
        if (p.swiping) return
        invalidate()
    }

    /** Letter key strictly under the point (no snapping to the nearest key). */
    private fun hitLetter(x: Float, y: Float): Cell? {
        for (c in cells) {
            if (c.key.type == "char" && c.key.output.length == 1 && c.key.output[0].isLetter() && c.rect.contains(x, y)) return c
        }
        return null
    }

    private fun letterCenters(): Map<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>()
        for (c in cells) if (c.key.type == "char" && c.key.output.length == 1 && c.key.output[0].isLetter())
            m[c.key.output.lowercase()[0]] = floatArrayOf(c.rect.centerX(), c.rect.centerY())
        return m
    }

    private fun keyWidth(): Float {
        val l = cells.filter { it.key.type == "char" && it.key.output.length == 1 && it.key.output[0].isLetter() }
        return if (l.isEmpty()) context.dpf(36f) else l.map { it.rect.width() }.average().toFloat()
    }

    // ---- desktop-style modifiers and key events
    private val mods = IntArray(3)            // ctrl, alt, meta: 0 off, 1 next key only, 2 locked
    private val modTap = LongArray(3)
    private fun modIdx(n: String?) = when (n) { "ctrl" -> 0; "alt" -> 1; "meta" -> 2; else -> -1 }
    private fun anyMod() = mods.any { it > 0 }

    private fun toggleMod(n: String?) {
        val i = modIdx(n); if (i < 0) return
        val now = SystemClock.uptimeMillis()
        mods[i] = when { mods[i] == 2 -> 0; mods[i] == 1 && now - modTap[i] < 350 -> 2; mods[i] == 1 -> 0; else -> 1 }
        modTap[i] = now
    }

    private fun clearOneShot() { for (i in mods.indices) if (mods[i] == 1) mods[i] = 0 }

    private fun metaNow(): Int {
        var m = 0
        if (mods[0] > 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (mods[1] > 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (mods[2] > 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (shiftState > 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return m
    }

    private fun charCode(c: Char): Int = when (c.lowercaseChar()) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        ',' -> KeyEvent.KEYCODE_COMMA; '.' -> KeyEvent.KEYCODE_PERIOD; '/' -> KeyEvent.KEYCODE_SLASH
        ';' -> KeyEvent.KEYCODE_SEMICOLON; '\'' -> KeyEvent.KEYCODE_APOSTROPHE; '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET; '\\' -> KeyEvent.KEYCODE_BACKSLASH; '-' -> KeyEvent.KEYCODE_MINUS
        '=' -> KeyEvent.KEYCODE_EQUALS; '`' -> KeyEvent.KEYCODE_GRAVE; ' ' -> KeyEvent.KEYCODE_SPACE
        else -> 0
    }

    private fun fireCode(code: Int, extra: Int = 0) {
        listener?.onKey(code, metaNow() or extra)
        clearOneShot(); if (shiftState == 1) afterChar()
    }

    /** spec like "ctrl+shift+z", "DPAD_UP", "F5". */
    private fun fireSpec(spec: String) {
        val parts = spec.split("+"); val name = parts.last(); var extra = 0
        for (m in parts.dropLast(1)) extra = extra or when (m.lowercase()) {
            "ctrl" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            "meta", "win" -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            else -> 0
        }
        val code = if (name.length == 1) charCode(name[0]) else KeyEvent.keyCodeFromString("KEYCODE_" + name.uppercase())
        if (code > 0) fireCode(code, extra)
    }

    private fun repeatable(spec: String) = Regex("(DPAD_.*|PAGE_.*|FORWARD_DEL|DEL|MOVE_.*)").matches(spec.substringAfterLast("+").uppercase())

    private fun afterChar() { if (shiftState == 1) { shiftState = 0; autoShifted = false; rebuild() } }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        p.lp?.let { handler.removeCallbacks(it) }; p.rep?.let { handler.removeCallbacks(it) }
        try {
            val alts = p.alts
            if (alts != null) {
                if (p.langMode) listener?.onSelectLayout(p.altSel)
                else alts.getOrNull(p.altSel)?.let { afterChar(); listener?.onText(it, layerName == "base") }
                return
            }
            if (p.swiping) {
                val s = shiftState > 0; afterChar()
                p.pts.add(x); p.pts.add(y)
                listener?.onSwipe(SwipeInput(p.pts.toFloatArray(), letterCenters(), keyWidth()), s); return
            }
            if (p.cursorMode) return
            val cell = p.cell ?: return
            if (p.longFired) return
            var target = cell
            if (p.moved && cell.key.type == "char") hit(x, y)?.let { target = it }
            val k = target.key
            when (k.type) {
                "char" -> if (k.output.isNotEmpty()) {
                    val cc = if (anyMod() && k.output.length == 1) charCode(k.output[0]) else 0
                    if (cc > 0) fireCode(cc) else { val letter = layerName == "base"; afterChar(); listener?.onText(k.output, letter) }
                }
                "stack" -> {
                    val n = max(1, k.hints.size)
                    val i = ((y - target.rect.top) / target.rect.height() * n).toInt().coerceIn(0, n - 1)
                    k.hints.getOrNull(i)?.let { listener?.onText(it, false) }
                }
                "shift" -> toggleShift()
                "layer" -> {
                    if (k.param == "letters") listener?.onSpecial("letters")
                    else { showLayer(k.param ?: "base"); listener?.onLayerChange(layerName) }
                }
                "del", "key" -> {}
                "mod" -> toggleMod(k.param)
                "act" -> listener?.onSpecial("act:" + (k.param ?: ""))
                "space" -> if (anyMod()) fireCode(KeyEvent.KEYCODE_SPACE) else listener?.onSpecial("space")
                "enter" -> if (anyMod()) fireCode(KeyEvent.KEYCODE_ENTER) else listener?.onSpecial("enter")
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
