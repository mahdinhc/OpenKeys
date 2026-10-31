package com.openkeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Playable piano. Everything played is typed as melody text (see MelodyParser) when all fingers lift. */
class PianoKeys(ctx: Context, private val synth: Synth, private val onMelody: (String) -> Unit) : View(ctx) {
    var whiteKeys = 14; var startOct = 4; var blackH = 0.62f; var labels = true; var sound = true
    var record = true; var timing = true; var chordMs = 60; var quant = 50
    var cWhite = Color.WHITE; var cBlack = Color.parseColor("#1A1A1A"); var cPress = Color.parseColor("#669DF6")

    private class Ev(val midi: Int, val down: Long) { var up = 0L }
    private val active = HashMap<Int, Ev>()
    private val done = ArrayList<Ev>()
    private var lastEnd = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val whiteSemi = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun reset() { lastEnd = 0L; done.clear(); active.clear(); synth.allOff(); invalidate() }
    private fun name(m: Int) = names[m % 12] + (m / 12 - 1)
    private fun whiteMidi(i: Int) = 12 * (startOct + 1) + 12 * (i / 7) + whiteSemi[i % 7]
    private fun hasBlack(i: Int) = i < whiteKeys - 1 && (i % 7) in intArrayOf(0, 1, 3, 4, 5)

    private fun blackRect(i: Int): RectF {
        val ww = width / whiteKeys.toFloat(); val bw = ww * 0.6f
        return RectF((i + 1) * ww - bw / 2, 0f, (i + 1) * ww + bw / 2, height * blackH)
    }

    private fun noteAt(x: Float, y: Float): Int? {
        if (width == 0) return null
        for (i in 0 until whiteKeys) if (hasBlack(i) && blackRect(i).contains(x, y)) return whiteMidi(i) + 1
        val i = (x / (width / whiteKeys.toFloat())).toInt().coerceIn(0, whiteKeys - 1)
        return whiteMidi(i)
    }

    override fun onDraw(c: Canvas) {
        val ww = width / whiteKeys.toFloat(); val g = context.dpf(1f); val r = context.dpf(6f)
        val down = active.values.map { it.midi }.toSet()
        for (i in 0 until whiteKeys) {
            val m = whiteMidi(i)
            p.color = if (m in down) cPress else cWhite
            c.drawRoundRect(RectF(i * ww + g, 0f, (i + 1) * ww - g, height.toFloat()), r, r, p)
            if (labels) {
                p.color = Color.argb(150, 0, 0, 0); p.textAlign = Paint.Align.CENTER
                p.textSize = min(ww * 0.4f, context.dpf(13f))
                c.drawText(if (i % 7 == 0) name(m) else names[m % 12], (i + 0.5f) * ww, height - context.dpf(8f), p)
            }
        }
        for (i in 0 until whiteKeys) if (hasBlack(i)) {
            p.color = if ((whiteMidi(i) + 1) in down) cPress else cBlack
            c.drawRoundRect(blackRect(i), r * 0.6f, r * 0.6f, p)
        }
    }

    private fun press(id: Int, m: Int) {
        if (sound) synth.noteOn(m, m)
        active[id] = Ev(m, SystemClock.uptimeMillis())
    }

    private fun lift(id: Int) {
        val e = active.remove(id) ?: return
        e.up = SystemClock.uptimeMillis()
        if (active.values.none { it.midi == e.midi }) synth.noteOff(e.midi)
        done.add(e)
        if (active.isEmpty()) flush()
    }

    private fun q(d: Long): Int { val s = quant.coerceAtLeast(10); return max(s, ((d + s / 2) / s * s).toInt()) }

    private fun flush() {
        val evs = done.sortedBy { it.down }; done.clear()
        if (!record || evs.isEmpty()) return
        val sb = StringBuilder()
        val first = evs.first().down
        if (timing && lastEnd > 0) { val gap = first - lastEnd; if (gap in 150..5000) sb.append("_:").append(q(gap)).append(' ') }
        val groups = ArrayList<ArrayList<Ev>>()
        for (e in evs) { val gp = groups.lastOrNull(); if (gp != null && e.down - gp[0].down <= chordMs) gp.add(e) else groups.add(arrayListOf(e)) }
        for (gp in groups) {
            val ns = gp.map { it.midi }.distinct().sorted().map { name(it) }
            val tok = if (ns.size == 1) ns[0] else "[" + ns.joinToString(" ") + "]"
            sb.append(tok)
            if (timing) sb.append(':').append(q(gp.maxOf { it.up } - gp.minOf { it.down }))
            sb.append(' ')
        }
        lastEnd = evs.maxOf { it.up }
        onMelody(sb.toString())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; noteAt(e.getX(i), e.getY(i))?.let { press(e.getPointerId(i), it) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); val cur = active[id] ?: continue
                val n = noteAt(e.getX(i), e.getY(i)) ?: continue
                if (n != cur.midi) { lift(id); press(id, n) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> lift(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> for (id in active.keys.toList()) lift(id)
        }
        invalidate(); return true
    }
}

class PianoPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onMelody(text: String); fun onBack(); fun onSpace(); fun onBackspace(); fun onEnter() }

    private val synth = Synth()
    private val keys = PianoKeys(ctx, synth) { cb.onMelody(it) }
    private val row = LinearLayout(ctx)
    private val btns = ArrayList<TextView>()
    private val octLabel: TextView
    private var theme = Theme.DARK

    init {
        orientation = VERTICAL
        row.orientation = HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        fun btn(t: String, w: Float, f: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; setOnClickListener { f() }
            btns.add(this); row.addView(this, LayoutParams(0, LayoutParams.MATCH_PARENT, w).also { it.setMargins(ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f)) })
        }
        btn("−", 0.8f) { shift(-1) }
        octLabel = btn("", 1f) {}
        btn("+", 0.8f) { shift(1) }
        btn("␣", 1.6f) { cb.onSpace() }
        btn("⌫", 1f) { cb.onBackspace() }
        btn("↵", 1f) { cb.onEnter() }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dpi(44f)))
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun shift(d: Int) { keys.startOct = (keys.startOct + d).coerceIn(0, 7); octLabel.text = "C${keys.startOct}"; keys.invalidate() }

    fun open(pr: Prefs) {
        fun col(k: String, d: String) = try { Color.parseColor(pr.str(k, d)) } catch (e: Exception) { Color.parseColor(d) }
        keys.apply {
            whiteKeys = pr.int("pianoKeys", 14).coerceIn(7, 21); startOct = pr.int("pianoOctave", 4).coerceIn(0, 7)
            blackH = pr.int("pianoBlackH", 62).coerceIn(35, 85) / 100f; labels = pr.bool("pianoLabels", true)
            sound = pr.bool("pianoSound", true); record = pr.bool("pianoRecord", true); timing = pr.bool("pianoTiming", true)
            chordMs = pr.int("pianoChordMs", 60); quant = pr.int("pianoQuant", 50)
            cWhite = col("pianoWhite", "#FFFFFF"); cBlack = col("pianoBlack", "#1A1A1A"); cPress = col("pianoPress", "#669DF6")
            reset()
        }
        synth.params = SynthParams.from(pr)
        octLabel.text = "C${keys.startOct}"
    }

    fun applyTheme(t: Theme) {
        theme = t; setBackgroundColor(t.bg)
        btns.forEach { it.setTextColor(t.keyText); it.background = GradientDrawable().apply { setColor(t.key); cornerRadius = context.dpf(10f) } }
    }

    fun silence() { synth.allOff() }
}
