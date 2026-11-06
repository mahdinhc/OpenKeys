package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * "Hold an emoji to send it as a sticker": draws any emoji (standard, skin-toned, ZWJ sequence or an OpenEmoji
 * Private-Use-Area emoji) with the emoji font chosen in Settings into a transparent 512x512 PNG.
 * The PNG lives in the clip folder (so the existing FileProvider / commitContent path sends it) and is cached per
 * font, so the same emoji is instant the second time.
 */
object EmojiSticker {
    private const val SIZE = 512
    private const val SCRATCH = 768
    private const val KEEP = 200                    // cached emoji stickers (oldest used are deleted first)
    private val main = Handler(Looper.getMainLooper())

    private fun fileFor(ctx: Context, e: String) = File(ClipStore.dir(ctx), "emoji_" + EmojiFont.mode(ctx) + "_" + EmojiKitchen.token(e) + ".png")

    /** Renders (or reuses) the sticker in the background; [done] runs on the main thread, null if the emoji can't be drawn. */
    fun make(ctx: Context, e: String, done: (File?) -> Unit) {
        val app = ctx.applicationContext
        val f = fileFor(app, e)
        if (f.exists() && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); done(f); return }
        Thread {
            val ok = try { render(app, e, f) } catch (t: Throwable) { DevLog.e(app, "Sticker", "render failed", t); false }
            if (ok) prune(app)
            main.post { done(if (ok) f else null) }
        }.apply { isDaemon = true }.start()
    }

    private fun render(app: Context, e: String, out: File): Boolean {
        // 1) draw big on a scratch bitmap, whatever the font's metrics are
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            typeface = EmojiFont.get(app); textSize = SCRATCH * 0.52f; textAlign = Paint.Align.CENTER
        }
        val scratch = Bitmap.createBitmap(SCRATCH, SCRATCH, Bitmap.Config.ARGB_8888)
        try {
            val fm = paint.fontMetrics
            Canvas(scratch).drawText(e, SCRATCH / 2f, SCRATCH / 2f - (fm.ascent + fm.descent) / 2f, paint)
            // 2) find the glyph's real bounds (non-transparent pixels)
            val px = IntArray(SCRATCH * SCRATCH)
            scratch.getPixels(px, 0, SCRATCH, 0, 0, SCRATCH, SCRATCH)
            var l = SCRATCH; var t = SCRATCH; var r = -1; var b = -1
            for (y in 0 until SCRATCH) for (x in 0 until SCRATCH) {
                if ((px[y * SCRATCH + x] ushr 24) > 8) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
            }
            if (r < l || b < t) return false                                  // nothing was drawn
            val bw = r - l + 1; val bh = b - t + 1
            // 3) centre it in the sticker with a small margin (never blow tiny glyphs up too far)
            val scale = minOf((SIZE * 0.92f) / bw, (SIZE * 0.92f) / bh, 1.6f)
            val dw = bw * scale; val dh = bh * scale
            val dst = RectF((SIZE - dw) / 2f, (SIZE - dh) / 2f, (SIZE + dw) / 2f, (SIZE + dh) / 2f)
            val png = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            try {
                Canvas(png).drawBitmap(scratch, Rect(l, t, r + 1, b + 1), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                val tmp = File(out.path + ".part")
                tmp.outputStream().use { png.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) { tmp.delete(); return false } }
            } finally { png.recycle() }
            return out.length() > 0
        } finally { scratch.recycle() }
    }

    private fun prune(app: Context) {
        val fs = ClipStore.dir(app).listFiles { f -> f.name.startsWith("emoji_") && f.name.endsWith(".png") } ?: return
        if (fs.size <= KEEP) return
        fs.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
    }
}
