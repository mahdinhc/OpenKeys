package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Drawing surface for plugins (keys.canvas). The plugin sends batched draw ops (JSON); they are painted onto a
 * persistent bitmap, so a plugin never has to re-send history. All coordinates/sizes are in dp.
 * Touches go back to the plugin as {plugin, cid, pts:[{t,id,x,y,p,s}]}.
 */
class CanvasPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback {
        fun onEvent(o: JSONObject)
        fun onBack()
        /** A PNG was written to ClipStore.dir(); insert it into the editor. */
        fun onInsert(file: String)
    }

    var plugin = ""; private set
    var cid = 0; private set
    private var theme = Theme.DARK
    private var bg = Color.WHITE
    private var title = ""
    private var buttons = JSONArray()
    private val d = ctx.resources.displayMetrics.density
    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private val stack = ArrayList<Bitmap>()

    private val back = TextView(ctx)
    private val scroll = HorizontalScrollView(ctx)
    private val row = LinearLayout(ctx)
    private val surface = Surface(ctx)

    private inner class Surface(c: Context) : View(c) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w <= 0 || h <= 0) return
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(nb)
            bmp?.let { c.drawBitmap(it, 0f, 0f, null); it.recycle() }
            bmp = nb; bc = c
            emitSize()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(bg)
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val a = JSONArray()
            fun r(v: Float) = (v / d * 10).roundToInt() / 10.0
            fun pt(t: String, i: Int, x: Float, y: Float) = a.put(JSONObject().put("t", t).put("id", e.getPointerId(i))
                .put("x", r(x)).put("y", r(y)).put("p", (e.getPressure(i) * 100).roundToInt() / 100.0)
                .put("s", e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS))
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { val i = e.actionIndex; pt("down", i, e.getX(i), e.getY(i)) }
                MotionEvent.ACTION_MOVE -> {
                    for (h in 0 until e.historySize) for (i in 0 until e.pointerCount) pt("move", i, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    for (i in 0 until e.pointerCount) pt("move", i, e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val i = e.actionIndex; pt("up", i, e.getX(i), e.getY(i)) }
                MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) pt("cancel", i, e.getX(i), e.getY(i))
            }
            if (a.length() > 0) cb.onEvent(base().put("pts", a))
            return true
        }
    }

    init {
        orientation = VERTICAL
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back.apply {
            text = "ABC"; textSize = 14f; gravity = Gravity.CENTER; isClickable = true
            setPadding(context.dpi(12f), 0, context.dpi(12f), 0)
            setOnClickListener { cb.onBack() }
        }
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(back, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(scroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(44f)))
        addView(surface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun base() = JSONObject().put("plugin", plugin).put("cid", cid)

    private fun emitSize() {
        if (plugin.isEmpty() || surface.width <= 0) return
        cb.onEvent(base().put("size", JSONObject().put("w", (surface.width / d).toDouble()).put("h", (surface.height / d).toDouble())))
    }

    /** Called by the keyboard whenever this panel is hidden. */
    fun closed() { if (plugin.isNotEmpty()) cb.onEvent(base().put("closed", true)) }

    fun show(plugin: String, cid: Int, spec: JSONObject) {
        this.plugin = plugin; this.cid = cid
        title = spec.optString("title", "")
        bg = col(spec.optString("bg", ""), Color.WHITE)
        buttons = JSONArray()
        stack.forEach { it.recycle() }; stack.clear()
        bc?.drawColor(0, PorterDuff.Mode.CLEAR)
        rebuildRow()
        surface.invalidate()
        surface.post { emitSize() }
    }

    fun applyTheme(th: Theme) {
        theme = th
        setBackgroundColor(th.bg)
        back.setTextColor(th.keyText)
        rebuildRow()
    }

    private fun rebuildRow() {
        val keep = scroll.scrollX
        row.removeAllViews()
        if (title.isNotEmpty()) row.addView(TextView(context).apply {
            text = title; textSize = 13f; setTextColor(theme.hint); gravity = Gravity.CENTER
            setPadding(context.dpi(6f), 0, context.dpi(10f), 0)
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        for (i in 0 until buttons.length()) {
            val b = buttons.optJSONObject(i) ?: continue
            val on = b.optBoolean("active", false)
            val t = TextView(context).apply {
                text = b.optString("label"); textSize = 15f; gravity = Gravity.CENTER; isClickable = true; maxLines = 1
                setPadding(context.dpi(11f), 0, context.dpi(11f), 0)
                setTextColor(if (on) theme.accentText else theme.keyText)
                background = GradientDrawable().apply { setColor(if (on) theme.accent else theme.key); cornerRadius = context.dpf(8f) }
                setOnClickListener { cb.onEvent(base().put("btn", i)) }
            }
            val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dpi(36f))
            lp.setMargins(context.dpi(3f), 0, context.dpi(3f), 0)
            row.addView(t, lp)
        }
        scroll.post { scroll.scrollTo(keep, 0) }
    }

    // ------------------------------------------------------------ drawing
    private fun col(s: String?, def: Int): Int {
        if (s.isNullOrEmpty()) return def
        return try {
            val t = s.trim()
            when {
                t == "transparent" -> 0
                t.startsWith("rgb") -> {
                    val n = Regex("[\\d.]+").findAll(t).map { it.value.toFloat() }.toList()
                    if (n.size < 3) def else Color.argb(((if (n.size > 3) n[3] else 1f) * 255).toInt().coerceIn(0, 255), n[0].toInt(), n[1].toInt(), n[2].toInt())
                }
                t.startsWith("#") && t.length == 4 -> Color.parseColor("#" + t.substring(1).map { "$it$it" }.joinToString(""))
                t.startsWith("#") && t.length == 9 -> Color.parseColor("#" + t.substring(7) + t.substring(1, 7))   // #rrggbbaa
                else -> Color.parseColor(t)
            }
        } catch (e: Exception) { def }
    }

    private fun mk(style: Paint.Style, color: Int, w: Float, o: JSONObject): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style; this.color = color; strokeWidth = w
        strokeCap = when (o.optString("cap")) { "butt" -> Paint.Cap.BUTT; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.ROUND }
        strokeJoin = Paint.Join.ROUND
        if (o.optInt("erase", 0) == 1) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private fun shape(c: Canvas, o: JSONObject, draw: (Canvas, Paint) -> Unit) {
        if (o.has("f")) draw(c, mk(Paint.Style.FILL, col(o.optString("f"), Color.BLACK), 0f, o))
        if (o.has("s")) draw(c, mk(Paint.Style.STROKE, col(o.optString("s"), Color.BLACK), o.optDouble("lw", 2.0).toFloat(), o))
    }

    private fun f(o: JSONObject, k: String) = o.optDouble(k, 0.0).toFloat()

    fun apply(ops: JSONArray) {
        val b = bmp ?: return
        val c = bc ?: return
        c.save(); c.scale(d, d)
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            try { one(c, b, o) } catch (e: Exception) { PluginLog.add(plugin, "error", "canvas op ${o.optString("o")}: ${e.message}") }
        }
        c.restore()
        surface.invalidate()
    }

    private fun one(c: Canvas, b: Bitmap, o: JSONObject) {
        when (o.optString("o")) {
            "clear" -> c.drawColor(0, PorterDuff.Mode.CLEAR)
            "bg" -> bg = col(o.optString("c"), Color.WHITE)
            "title" -> { title = o.optString("t"); rebuildRow() }
            "ui" -> { buttons = o.optJSONArray("buttons") ?: JSONArray(); rebuildRow() }
            "save" -> { stack.add(b.copy(Bitmap.Config.ARGB_8888, false)); if (stack.size > 30) stack.removeAt(0).recycle() }
            "restore" -> if (stack.isNotEmpty()) {
                val s = stack.removeAt(stack.size - 1)
                c.save(); c.setMatrix(null)
                c.drawColor(0, PorterDuff.Mode.CLEAR); c.drawBitmap(s, 0f, 0f, null)
                c.restore(); s.recycle()
            }
            "line" -> shape(c, o) { cc, p -> cc.drawLine(f(o, "x1"), f(o, "y1"), f(o, "x2"), f(o, "y2"), p) }
            "rect" -> {
                val x = f(o, "x"); val y = f(o, "y"); val r = f(o, "r")
                shape(c, o) { cc, p -> if (r > 0f) cc.drawRoundRect(x, y, x + f(o, "w"), y + f(o, "h"), r, r, p) else cc.drawRect(x, y, x + f(o, "w"), y + f(o, "h"), p) }
            }
            "circle" -> shape(c, o) { cc, p -> cc.drawCircle(f(o, "x"), f(o, "y"), f(o, "r"), p) }
            "oval" -> shape(c, o) { cc, p -> cc.drawOval(f(o, "x"), f(o, "y"), f(o, "x") + f(o, "w"), f(o, "y") + f(o, "h"), p) }
            "path" -> path(c, o)
            "text" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = col(o.optString("c"), Color.BLACK); textSize = o.optDouble("size", 16.0).toFloat()
                    textAlign = when (o.optString("align")) { "center" -> Paint.Align.CENTER; "right" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                    if (o.optInt("bold", 0) == 1) typeface = Typeface.DEFAULT_BOLD
                    if (o.optInt("erase", 0) == 1) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                c.drawText(o.optString("t"), f(o, "x"), f(o, "y"), p)
            }
            "insert" -> export(b, o.optBoolean("transparent", false))
        }
    }

    private fun path(c: Canvas, o: JSONObject) {
        val a = o.optJSONArray("pts") ?: return
        val n = a.length() / 2
        if (n == 0) return
        fun x(i: Int) = a.optDouble(i * 2).toFloat()
        fun y(i: Int) = a.optDouble(i * 2 + 1).toFloat()
        if (n == 1) {
            val colr = col(if (o.has("s")) o.optString("s") else o.optString("f"), Color.BLACK)
            c.drawCircle(x(0), y(0), f(o, "lw") / 2f, mk(Paint.Style.FILL, colr, 0f, o))
            return
        }
        val p = Path()
        p.moveTo(x(0), y(0))
        if (o.optInt("smooth", 0) == 1 && n > 2) {
            for (i in 1 until n - 1) p.quadTo(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
            p.lineTo(x(n - 1), y(n - 1))
        } else for (i in 1 until n) p.lineTo(x(i), y(i))
        if (o.optInt("closed", 0) == 1) p.close()
        shape(c, o) { cc, pt -> cc.drawPath(p, pt) }
    }

    private fun export(b: Bitmap, transparent: Boolean) {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        if (!transparent) oc.drawColor(bg)
        oc.drawBitmap(b, 0f, 0f, null)
        val name = "canvas_" + System.currentTimeMillis() + ".png"
        File(ClipStore.dir(context), name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        cb.onInsert(name)
    }
}
