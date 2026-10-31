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
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.Base64
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
import kotlin.math.max
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
    private val cm = Matrix()                       // plugin-controlled transform (translate/rotate/scale)
    private val tfStack = ArrayList<Matrix>()
    private var clip: Path? = null                  // device-space clip
    private val clipStack = ArrayList<Path?>()
    private val images = HashMap<String, Bitmap>()
    private val io = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "openkeys-img").also { it.isDaemon = true } }

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
        cm.reset(); tfStack.clear(); clip = null; clipStack.clear(); scroll.visibility = View.VISIBLE
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

    /** A color value is a string, or {type:"linear"|"radial"|"sweep", ..., stops:[[pos,color],...]}. */
    private fun shader(g: JSONObject): Shader? {
        val st = g.optJSONArray("stops") ?: return null
        val n = st.length()
        if (n < 2) return null
        val cols = IntArray(n); val pos = FloatArray(n)
        for (i in 0 until n) { val a = st.getJSONArray(i); pos[i] = a.getDouble(0).toFloat(); cols[i] = col(a.getString(1), Color.BLACK) }
        fun f(k: String) = g.optDouble(k, 0.0).toFloat()
        return when (g.optString("type", "linear")) {
            "radial" -> RadialGradient(f("x"), f("y"), max(0.01f, f("r")), cols, pos, Shader.TileMode.CLAMP)
            "sweep" -> SweepGradient(f("x"), f("y"), cols, pos)
            else -> LinearGradient(f("x1"), f("y1"), f("x2"), f("y2"), cols, pos, Shader.TileMode.CLAMP)
        }
    }

    private fun mk(style: Paint.Style, v: Any?, w: Float, o: JSONObject): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style; strokeWidth = w
        if (v is JSONObject) { shader = shader(v); color = Color.BLACK } else color = col(v?.toString(), Color.BLACK)
        strokeCap = when (o.optString("cap")) { "butt" -> Paint.Cap.BUTT; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.ROUND }
        strokeJoin = when (o.optString("join")) { "miter" -> Paint.Join.MITER; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.ROUND }
        if (o.has("alpha")) alpha = (alpha * o.optDouble("alpha", 1.0).coerceIn(0.0, 1.0)).toInt()
        val d = o.optJSONArray("dash")
        if (d != null && d.length() >= 2) pathEffect = DashPathEffect(floatArrayOf(d.optDouble(0, 4.0).toFloat(), d.optDouble(1, 4.0).toFloat()), 0f)
        val sh = o.optJSONObject("shadow")
        if (sh != null) setShadowLayer(sh.optDouble("blur", 4.0).toFloat(), sh.optDouble("dx", 0.0).toFloat(), sh.optDouble("dy", 2.0).toFloat(), col(sh.optString("color"), 0x80000000.toInt()))
        xfermode = when {
            o.optInt("erase", 0) == 1 -> PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            else -> when (o.optString("blend")) {
                "multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY); "screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                "overlay" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY); "darken" -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                "lighten" -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN); "add" -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                "xor" -> PorterDuffXfermode(PorterDuff.Mode.XOR); "src" -> PorterDuffXfermode(PorterDuff.Mode.SRC)
                "in" -> PorterDuffXfermode(PorterDuff.Mode.SRC_IN); "out" -> PorterDuffXfermode(PorterDuff.Mode.SRC_OUT)
                "atop" -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                else -> null
            }
        }
    }

    private fun shape(c: Canvas, o: JSONObject, draw: (Canvas, Paint) -> Unit) {
        if (o.has("f")) draw(c, mk(Paint.Style.FILL, o.opt("f"), 0f, o))
        if (o.has("s")) draw(c, mk(Paint.Style.STROKE, o.opt("s"), o.optDouble("lw", 2.0).toFloat(), o))
    }

    private fun f(o: JSONObject, k: String) = o.optDouble(k, 0.0).toFloat()

    /** Re-establishes clip + dp scale + plugin transform on a fresh save level. */
    private fun setup(c: Canvas) {
        clip?.let { c.clipPath(it) }
        c.scale(d, d)
        c.concat(cm)
    }

    fun apply(ops: JSONArray) {
        val b = bmp ?: return
        val c = bc ?: return
        c.save(); setup(c)
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            try {
                if (o.optString("o") == "tf") { tf(o); c.restore(); c.save(); setup(c) }
                else one(c, b, o)
            } catch (e: Exception) { PluginLog.add(plugin, "error", "canvas op ${o.optString("o")}: ${e.message}") }
        }
        c.restore()
        surface.invalidate()
    }

    private fun devMatrix() = Matrix().also { it.setScale(d, d); it.preConcat(cm) }

    private fun tf(o: JSONObject) {
        when (o.optString("k")) {
            "translate" -> cm.preTranslate(f(o, "x"), f(o, "y"))
            "rotate" -> if (o.has("x")) cm.preRotate(f(o, "a"), f(o, "x"), f(o, "y")) else cm.preRotate(f(o, "a"))
            "scale" -> if (o.has("px")) cm.preScale(f(o, "x"), f(o, "y"), f(o, "px"), f(o, "py")) else cm.preScale(f(o, "x"), f(o, "y"))
            "reset" -> cm.reset()
            "push" -> { tfStack.add(Matrix(cm)); clipStack.add(clip?.let { Path(it) }) }
            "pop" -> if (tfStack.isNotEmpty()) { cm.set(tfStack.removeAt(tfStack.size - 1)); clip = clipStack.removeAt(clipStack.size - 1) }
            "clipRect", "clipCircle" -> {
                val p = Path()
                if (o.optString("k") == "clipRect") { val x = f(o, "x"); val y = f(o, "y"); p.addRect(x, y, x + f(o, "w"), y + f(o, "h"), Path.Direction.CW) }
                else p.addCircle(f(o, "x"), f(o, "y"), f(o, "r"), Path.Direction.CW)
                p.transform(devMatrix())
                val cur = clip
                if (cur == null) clip = p else { val r = Path(cur); r.op(p, Path.Op.INTERSECT); clip = r }
            }
            "clipReset" -> clip = null
        }
    }

    private fun one(c: Canvas, b: Bitmap, o: JSONObject) {
        when (o.optString("o")) {
            "clear" -> Canvas(b).drawColor(0, PorterDuff.Mode.CLEAR)
            "bg" -> bg = col(o.optString("c"), Color.WHITE)
            "title" -> { title = o.optString("t"); rebuildRow() }
            "ui" -> { buttons = o.optJSONArray("buttons") ?: JSONArray(); rebuildRow() }
            "bar" -> scroll.visibility = if (o.optBoolean("show", true)) View.VISIBLE else View.GONE
            "save" -> { stack.add(b.copy(Bitmap.Config.ARGB_8888, false)); if (stack.size > 30) stack.removeAt(0).recycle() }
            "restore" -> if (stack.isNotEmpty()) {
                val s = stack.removeAt(stack.size - 1)
                val raw = Canvas(b)
                raw.drawColor(0, PorterDuff.Mode.CLEAR); raw.drawBitmap(s, 0f, 0f, null)
                s.recycle()
            }
            "line" -> shape(c, o) { cc, p -> cc.drawLine(f(o, "x1"), f(o, "y1"), f(o, "x2"), f(o, "y2"), p) }
            "rect" -> {
                val x = f(o, "x"); val y = f(o, "y"); val r = f(o, "r")
                shape(c, o) { cc, p -> if (r > 0f) cc.drawRoundRect(x, y, x + f(o, "w"), y + f(o, "h"), r, r, p) else cc.drawRect(x, y, x + f(o, "w"), y + f(o, "h"), p) }
            }
            "circle" -> shape(c, o) { cc, p -> cc.drawCircle(f(o, "x"), f(o, "y"), f(o, "r"), p) }
            "oval" -> shape(c, o) { cc, p -> cc.drawOval(f(o, "x"), f(o, "y"), f(o, "x") + f(o, "w"), f(o, "y") + f(o, "h"), p) }
            "arc" -> shape(c, o) { cc, p ->
                cc.drawArc(RectF(f(o, "x"), f(o, "y"), f(o, "x") + f(o, "w"), f(o, "y") + f(o, "h")), f(o, "a0"), f(o, "sweep"), o.optInt("center", 0) == 1, p)
            }
            "bezier" -> {
                val a = o.optJSONArray("pts") ?: return
                val p = Path(); p.moveTo(a.optDouble(0).toFloat(), a.optDouble(1).toFloat())
                if (a.length() >= 8) p.cubicTo(a.optDouble(2).toFloat(), a.optDouble(3).toFloat(), a.optDouble(4).toFloat(), a.optDouble(5).toFloat(), a.optDouble(6).toFloat(), a.optDouble(7).toFloat())
                else p.quadTo(a.optDouble(2).toFloat(), a.optDouble(3).toFloat(), a.optDouble(4).toFloat(), a.optDouble(5).toFloat())
                shape(c, o) { cc, pt -> cc.drawPath(p, pt) }
            }
            "path" -> path(c, o)
            "text" -> text(c, o)
            "image" -> image(c, o)
            "flood" -> flood(b, o)
            "snap" -> snap(b, o)
            "insert" -> export(b, o.optBoolean("transparent", false))
        }
    }

    private fun text(c: Canvas, o: JSONObject) {
        val size = o.optDouble("size", 16.0).toFloat()
        val face = when (o.optString("font")) { "serif" -> Typeface.SERIF; "mono" -> Typeface.MONOSPACE; else -> Typeface.DEFAULT }
        val st = (if (o.optInt("bold", 0) == 1) Typeface.BOLD else 0) or (if (o.optInt("italic", 0) == 1) Typeface.ITALIC else 0)
        fun mkp(style: Paint.Style, v: Any?, w: Float) = mk(style, v, w, o).apply {
            textSize = size; typeface = if (st == 0) face else Typeface.create(face, st)
            textAlign = when (o.optString("align")) { "center" -> Paint.Align.CENTER; "right" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        }
        val t = o.optString("t"); val x = f(o, "x"); val y = f(o, "y")
        if (o.has("s")) c.drawText(t, x, y, mkp(Paint.Style.STROKE, o.opt("s"), o.optDouble("lw", 1.0).toFloat()))
        c.drawText(t, x, y, mkp(Paint.Style.FILL, o.opt("c") ?: "#000000", 0f))
    }

    private fun path(c: Canvas, o: JSONObject) {
        val a = o.optJSONArray("pts") ?: return
        val n = a.length() / 2
        if (n == 0) return
        fun x(i: Int) = a.optDouble(i * 2).toFloat()
        fun y(i: Int) = a.optDouble(i * 2 + 1).toFloat()
        if (n == 1) {
            c.drawCircle(x(0), y(0), f(o, "lw") / 2f, mk(Paint.Style.FILL, if (o.has("s")) o.opt("s") else o.opt("f"), 0f, o))
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

    // ---- images: data URL / base64 / http(s) URL
    private fun decode(src: String): Bitmap? = try {
        val b64 = if (src.startsWith("data:")) src.substringAfter(",") else src
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }

    private fun image(c: Canvas, o: JSONObject) {
        val src = o.optString("src")
        val x = f(o, "x"); val y = f(o, "y")
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (o.optDouble("alpha", 1.0).coerceIn(0.0, 1.0) * 255).toInt() }
        fun draw(cc: Canvas, bm: Bitmap) {
            val w = if (o.has("w")) f(o, "w") else bm.width / d
            val h = if (o.has("h")) f(o, "h") else if (o.has("w")) w * bm.height / bm.width else bm.height / d
            cc.drawBitmap(bm, null, RectF(x, y, x + w, y + h), p)
        }
        images[src]?.let { draw(c, it); return }
        if (src.startsWith("http")) {
            val cap = Matrix(cm); val cid0 = cid
            io.execute {
                val bm = try { java.net.URL(src).openStream().use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
                if (bm != null) surface.post {
                    if (cid != cid0) return@post
                    images[src] = bm
                    val cc = bc ?: return@post
                    cc.save(); cc.scale(d, d); cc.concat(cap); draw(cc, bm); cc.restore(); surface.invalidate()
                } else PluginLog.add(plugin, "error", "canvas image: could not load $src")
            }
            return
        }
        val bm = decode(src) ?: run { PluginLog.add(plugin, "error", "canvas image: bad data"); return }
        if (images.size > 20) images.clear()
        images[src] = bm
        draw(c, bm)
    }

    // ---- bucket fill (scanline, tolerance 0-255 per channel)
    private fun flood(b: Bitmap, o: JSONObject) {
        val w = b.width; val h = b.height
        val sx = (f(o, "x") * d).toInt(); val sy = (f(o, "y") * d).toInt()
        if (sx !in 0 until w || sy !in 0 until h) return
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val target = px[sy * w + sx]
        val fillC = col(o.optString("c"), Color.BLACK)
        val tol = o.optInt("tol", 24).coerceIn(0, 255)
        if (target == fillC && tol == 0) return
        fun near(a: Int): Boolean {
            if (a == target) return true
            if (tol == 0) return false
            return Math.abs(Color.alpha(a) - Color.alpha(target)) <= tol && Math.abs(Color.red(a) - Color.red(target)) <= tol &&
                Math.abs(Color.green(a) - Color.green(target)) <= tol && Math.abs(Color.blue(a) - Color.blue(target)) <= tol
        }
        val seen = BooleanArray(w * h)
        val stack = java.util.ArrayDeque<Int>()
        stack.push(sy * w + sx)
        while (stack.isNotEmpty()) {
            val p = stack.pop()
            var x = p % w; val y = p / w
            while (x >= 0 && !seen[y * w + x] && near(px[y * w + x])) x--
            x++
            var up = false; var down = false
            while (x < w && !seen[y * w + x] && near(px[y * w + x])) {
                val i = y * w + x
                seen[i] = true
                if (y > 0) { val ok = !seen[i - w] && near(px[i - w]); if (ok && !up) { stack.push(i - w); up = true } else if (!ok) up = false }
                if (y < h - 1) { val ok = !seen[i + w] && near(px[i + w]); if (ok && !down) { stack.push(i + w); down = true } else if (!ok) down = false }
                x++
            }
        }
        // fill + grow by 1px so anti-aliased edges don't leave a halo
        val out = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var hit = seen[i]
            if (!hit && ((x > 0 && seen[i - 1]) || (x < w - 1 && seen[i + 1]) || (y > 0 && seen[i - w]) || (y < h - 1 && seen[i + w]))) hit = tol > 0 && px[i] != 0 && Color.alpha(px[i]) < 255
            if (hit) out[i] = fillC
        }
        b.setPixels(out, 0, w, 0, 0, w, h)
    }

    private fun composed(b: Bitmap, transparent: Boolean): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        if (!transparent) oc.drawColor(bg)
        oc.drawBitmap(b, 0f, 0f, null)
        return out
    }

    private fun snap(b: Bitmap, o: JSONObject) {
        var out = composed(b, o.optBoolean("transparent", false))
        val mx = o.optInt("max", 0)
        if (mx > 0 && max(out.width, out.height) > mx) {
            val k = mx.toFloat() / max(out.width, out.height)
            out = Bitmap.createScaledBitmap(out, max(1, (out.width * k).toInt()), max(1, (out.height * k).toInt()), true)
        }
        val bos = java.io.ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, bos)
        cb.onEvent(base().put("snap", o.optInt("id")).put("data", "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
    }

    private fun export(b: Bitmap, transparent: Boolean) {
        val out = composed(b, transparent)
        val name = "canvas_" + System.currentTimeMillis() + ".png"
        File(ClipStore.dir(context), name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        cb.onInsert(name)
    }
}
