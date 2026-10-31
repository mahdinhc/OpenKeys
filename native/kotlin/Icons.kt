package com.openkeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONObject
import kotlin.math.min

/** Material-style vector icons from assets/icons.json (same file the React Native app uses). */
object Icons {
    private var defs: Map<String, String> = emptyMap()
    private var outline: Set<String> = emptySet()
    private val cache = HashMap<String, Path>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tok = Regex("[MmLlHhVvCcSsZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    fun load(ctx: Context) {
        if (defs.isNotEmpty()) return
        try {
            val o = JSONObject(ctx.assets.open("icons.json").bufferedReader().use { it.readText() })
            val ic = o.getJSONObject("icons")
            val m = HashMap<String, String>()
            ic.keys().forEach { m[it] = ic.getString(it) }
            defs = m
            outline = jsonStrings(o.optJSONArray("outline")).toSet()
        } catch (e: Exception) { DevLog.e(ctx, "Icons", "icons.json failed to load", e) }
    }

    private fun path(name: String): Path? {
        cache[name]?.let { return it }
        val d = defs[name] ?: return null
        val p = parse(d) ?: return null
        cache[name] = p
        return p
    }

    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val p = path(name) ?: return
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(size / 24f, size / 24f)
        paint.color = color
        if (name in outline) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.9f; paint.strokeJoin = Paint.Join.ROUND
        } else paint.style = Paint.Style.FILL
        c.drawPath(p, paint)
        c.restore()
    }

    private fun parse(d: String): Path? = try {
        val t = tok.findAll(d).map { it.value }.toList()
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD
        var i = 0
        var cmd = ' '
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f; var hasLc = false
        fun num(): Float = t[i++].toFloat()
        while (i < t.size) {
            if (t[i][0].isLetter()) { cmd = t[i][0]; i++ }
            val rel = cmd.isLowerCase()
            var u = cmd.uppercaseChar()
            if (u == 'Z') { p.close(); x = sx; y = sy; hasLc = false; continue }
            do {
                when (u) {
                    'M' -> {
                        val a = num(); val b = num()
                        x = if (rel) x + a else a; y = if (rel) y + b else b
                        sx = x; sy = y; p.moveTo(x, y); u = 'L'; hasLc = false
                    }
                    'L' -> {
                        val a = num(); val b = num()
                        x = if (rel) x + a else a; y = if (rel) y + b else b
                        p.lineTo(x, y); hasLc = false
                    }
                    'H' -> { val a = num(); x = if (rel) x + a else a; p.lineTo(x, y); hasLc = false }
                    'V' -> { val a = num(); y = if (rel) y + a else a; p.lineTo(x, y); hasLc = false }
                    'C' -> {
                        var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var x3 = num(); var y3 = num()
                        if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; x3 += x; y3 += y }
                        p.cubicTo(x1, y1, x2, y2, x3, y3)
                        lcx = x2; lcy = y2; hasLc = true; x = x3; y = y3
                    }
                    'S' -> {
                        var x2 = num(); var y2 = num(); var x3 = num(); var y3 = num()
                        if (rel) { x2 += x; y2 += y; x3 += x; y3 += y }
                        val x1 = if (hasLc) 2 * x - lcx else x
                        val y1 = if (hasLc) 2 * y - lcy else y
                        p.cubicTo(x1, y1, x2, y2, x3, y3)
                        lcx = x2; lcy = y2; hasLc = true; x = x3; y = y3
                    }
                    else -> throw IllegalStateException("bad path command $u")
                }
            } while (i < t.size && !t[i][0].isLetter())
        }
        p
    } catch (e: Exception) { null }
}

/** A tinted icon as a View. */
class IconView(ctx: Context, var icon: String, var tint: Int = Color.WHITE, private val frac: Float = 0.58f) : View(ctx) {
    init { Icons.load(ctx) }
    fun set(icon: String, tint: Int) { this.icon = icon; this.tint = tint; invalidate() }
    override fun onDraw(c: Canvas) {
        Icons.draw(c, icon, width / 2f, height / 2f, min(width, height) * frac, tint)
    }
}
