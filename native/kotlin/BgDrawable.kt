package com.openkeys.keyboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Keyboard background image settings (all stored in the shared config JSON, see src/config.ts). */
class BgStyle(p: Prefs) {
    val path = p.str("bgPath", "")
    val rev = p.cfg.optLong("bgRev", 0)
    val fit = p.str("bgFit", "cover")
    val blend = p.str("bgBlend", "normal")
    val opacity = p.int("bgOpacity", 100).coerceIn(0, 100)
    val blur = p.int("bgBlur", 0).coerceIn(0, 100)
    val gray = p.int("bgGray", 0).coerceIn(0, 100)
    val sepia = p.int("bgSepia", 0).coerceIn(0, 100)
    val bright = p.int("bgBright", 100).coerceIn(0, 250)
    val contrast = p.int("bgContrast", 100).coerceIn(0, 250)
    val sat = p.int("bgSat", 100).coerceIn(0, 300)
    val hue = p.int("bgHue", 0).coerceIn(-180, 180)
    val invert = p.bool("bgInvert", false)
    val dim = p.int("bgDim", 0).coerceIn(0, 100)
    val tint = p.str("bgTint", "#000000")
    val tintAmt = p.int("bgTintAmt", 0).coerceIn(0, 100)
    val flipX = p.bool("bgFlipX", false)
    val scale = p.int("bgScale", 100).coerceIn(100, 400)
    /** How opaque the emoji/clipboard/plugin panels stay (0-100). 100 = the image does not show through them. */
    val panels = p.int("bgPanels", 80).coerceIn(0, 100)
    val active get() = path.isNotEmpty() && File(path).exists()
}

/** Draws the theme background color, then the (decoded, optionally blurred) image with color effects on top. */
class BgDrawable : Drawable() {
    var color = Color.BLACK
    private var style: BgStyle? = null
    private var src: Bitmap? = null
    private var shown: Bitmap? = null     // src, blurred
    private var srcKey = ""
    private var blurKey = -1
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val over = Paint()
    private val m = Matrix()

    fun set(s: BgStyle, bgColor: Int) {
        color = bgColor
        style = s
        if (!s.active) { src = null; shown = null; srcKey = ""; invalidateSelf(); return }
        val k = s.path + "@" + s.rev
        if (k != srcKey) {
            srcKey = k; blurKey = -1
            src = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(s.path, o)
                var sample = 1
                while (max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2
                BitmapFactory.decodeFile(s.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Throwable) { null }
        }
        if (blurKey != s.blur) { blurKey = s.blur; shown = src?.let { blur(it, s.blur) } }
        invalidateSelf()
    }

    private fun blur(b: Bitmap, amount: Int): Bitmap {
        if (amount <= 0) return b
        val f = 1f / (1f + amount / 6f)
        val w = max(8, (b.width * f).toInt()); val h = max(8, (b.height * f).toInt())
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val r = max(1, min(w, h) / 40)
        repeat(2) { box(px, w, h, r, true); box(px, w, h, r, false) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun box(p: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h
        val lines = if (horiz) h else w
        val tmp = IntArray(n)
        for (l in 0 until lines) {
            fun at(i: Int) = if (horiz) l * w + i else i * w + l
            var a = 0L; var rr = 0L; var g = 0L; var bb = 0L
            fun add(i: Int, sgn: Int) { val c = p[at(i.coerceIn(0, n - 1))]; a += sgn * (c ushr 24); rr += sgn * ((c shr 16) and 255); g += sgn * ((c shr 8) and 255); bb += sgn * (c and 255) }
            for (i in -r..r) add(i, 1)
            val d = (2 * r + 1).toLong()
            for (i in 0 until n) {
                tmp[i] = ((a / d).toInt() shl 24) or ((rr / d).toInt() shl 16) or ((g / d).toInt() shl 8) or (bb / d).toInt()
                add(i + r + 1, 1); add(i - r, -1)
            }
            for (i in 0 until n) p[at(i)] = tmp[i]
        }
    }

    private fun matrix(s: BgStyle): ColorMatrix {
        val cm = ColorMatrix()
        val sat = (1f - s.gray / 100f) * (s.sat / 100f)
        if (sat != 1f) cm.postConcat(ColorMatrix().apply { setSaturation(sat) })
        if (s.sepia > 0) {
            val t = s.sepia / 100f
            val sp = floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            cm.postConcat(ColorMatrix(FloatArray(20) { id[it] * (1 - t) + sp[it] * t }))
        }
        if (s.hue != 0) {
            val a = Math.toRadians(s.hue.toDouble()); val c = cos(a).toFloat(); val sn = sin(a).toFloat()
            cm.postConcat(ColorMatrix(floatArrayOf(
                0.213f + c * 0.787f - sn * 0.213f, 0.715f - c * 0.715f - sn * 0.715f, 0.072f - c * 0.072f + sn * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + sn * 0.143f, 0.715f + c * 0.285f + sn * 0.140f, 0.072f - c * 0.072f - sn * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - sn * 0.787f, 0.715f - c * 0.715f + sn * 0.715f, 0.072f + c * 0.928f + sn * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f)))
        }
        if (s.bright != 100 || s.contrast != 100) {
            val c = s.contrast / 100f; val b = s.bright / 100f
            val sc = c * b; val tr = (128f - 128f * c) * b
            cm.postConcat(ColorMatrix(floatArrayOf(sc, 0f, 0f, 0f, tr, 0f, sc, 0f, 0f, tr, 0f, 0f, sc, 0f, tr, 0f, 0f, 0f, 1f, 0f)))
        }
        if (s.invert) cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        return cm
    }

    override fun draw(c: Canvas) {
        val r = bounds
        c.drawColor(color)
        val s = style ?: return
        val b = shown ?: return
        if (r.width() <= 0 || r.height() <= 0) return
        paint.alpha = (s.opacity * 255 / 100)
        paint.colorFilter = ColorMatrixColorFilter(matrix(s))
        paint.xfermode = if (s.blend == "normal") null else PorterDuffXfermode(when (s.blend) {
            "multiply" -> PorterDuff.Mode.MULTIPLY; "screen" -> PorterDuff.Mode.SCREEN; "overlay" -> PorterDuff.Mode.OVERLAY
            "darken" -> PorterDuff.Mode.DARKEN; "lighten" -> PorterDuff.Mode.LIGHTEN; "add" -> PorterDuff.Mode.ADD
            else -> PorterDuff.Mode.SRC_OVER
        })
        paint.shader = null
        val rw = r.width().toFloat(); val rh = r.height().toFloat()
        val z = s.scale / 100f
        c.save()
        c.clipRect(r)
        if (s.flipX) c.scale(-1f, 1f, rw / 2f, rh / 2f)
        if (s.fit == "tile") {
            paint.shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { sh ->
                val mm = Matrix(); val k = z * (src?.width?.toFloat() ?: b.width.toFloat()) / b.width * 0.5f; mm.setScale(k, k); sh.setLocalMatrix(mm)
            }
            c.drawRect(r, paint)
        } else {
            val bw = b.width.toFloat(); val bh = b.height.toFloat()
            m.reset()
            when (s.fit) {
                "stretch" -> m.setScale(rw / bw * z, rh / bh * z, 0f, 0f)
                "fit" -> { val k = min(rw / bw, rh / bh) * z; m.setScale(k, k) }
                else -> { val k = max(rw / bw, rh / bh) * z; m.setScale(k, k) }
            }
            val vals = FloatArray(9); m.getValues(vals)
            m.postTranslate((rw - bw * vals[Matrix.MSCALE_X]) / 2f, (rh - bh * vals[Matrix.MSCALE_Y]) / 2f)
            c.drawBitmap(b, m, paint)
        }
        c.restore()
        paint.shader = null
        if (s.tintAmt > 0) { over.color = try { Color.parseColor(s.tint) } catch (e: Exception) { Color.BLACK }; over.alpha = s.tintAmt * 255 / 100; c.drawRect(r, over) }
        if (s.dim > 0) { over.color = Color.BLACK; over.alpha = s.dim * 255 / 100; c.drawRect(r, over) }
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("") override fun getOpacity() = PixelFormat.OPAQUE
}
