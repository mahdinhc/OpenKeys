package com.openkeys.keyboard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Synth settings (times in seconds, sustain/volume/cutoff 0..1). */
class SynthParams(
    val wave: Int, val attack: Float, val decay: Float, val sustain: Float, val release: Float,
    val volume: Float, val cutoff: Float, val vibRate: Float, val vibDepth: Float, val detune: Float, val gateMs: Int
) {
    companion object {
        fun from(p: Prefs) = SynthParams(
            when (p.str("pianoWave", "sine")) { "square" -> 1; "saw" -> 2; "triangle" -> 3; else -> 0 },
            p.int("pianoAttack", 5).coerceIn(1, 5000) / 1000f, p.int("pianoDecay", 120).coerceIn(1, 5000) / 1000f,
            p.int("pianoSustain", 60).coerceIn(0, 100) / 100f, p.int("pianoRelease", 200).coerceIn(5, 8000) / 1000f,
            p.int("pianoVolume", 80).coerceIn(0, 100) / 100f, p.int("pianoCutoff", 100).coerceIn(2, 100) / 100f,
            p.int("pianoVibRate", 5).toFloat(), p.int("pianoVibDepth", 0).toFloat(), p.int("pianoDetune", 0).toFloat(),
            p.int("pianoGate", 0).coerceAtLeast(0)
        )
    }
}

/** Small polyphonic synth: osc (sine/square/saw/triangle) + detune + vibrato + low-pass + ADSR + gate time. */
class Synth {
    companion object { const val SR = 44100 }

    @Volatile var params: SynthParams = SynthParams(0, 0.005f, 0.12f, 0.6f, 0.2f, 0.8f, 1f, 5f, 0f, 0f, 0)

    private class Voice(val id: Int, val freq: Double) {
        var ph = 0.0; var ph2 = 0.0; var level = 0f; var stage = 0; var relStep = 0f; var age = 0; var lp = 0f
    }

    private val voices = ArrayList<Voice>()
    private val lock = Object()
    private var running = false
    private var lfo = 0.0

    fun noteOn(id: Int, midi: Int) {
        synchronized(lock) {
            voices.removeAll { it.id == id }
            if (voices.size >= 14) voices.removeAt(0)
            voices.add(Voice(id, 440.0 * 2.0.pow((midi - 69) / 12.0)))
            if (!running) { running = true; Thread { loop() }.also { it.priority = Thread.MAX_PRIORITY }.start() }
        }
    }

    private fun release(v: Voice, p: SynthParams) { v.stage = 3; v.relStep = max(v.level, 0.001f) / (p.release * SR) }

    fun noteOff(id: Int) {
        val p = params
        synchronized(lock) { voices.firstOrNull { it.id == id && it.stage != 3 }?.let { release(it, p) } }
    }

    fun allOff() { val p = params; synchronized(lock) { voices.forEach { if (it.stage != 3) release(it, p) } } }

    private fun wave(w: Int, ph: Double): Float = when (w) {
        0 -> sin(2 * PI * ph).toFloat()
        1 -> if (ph < 0.5) 0.8f else -0.8f
        2 -> (2 * ph - 1).toFloat()
        else -> (4 * abs(ph - 0.5) - 1).toFloat()
    }

    private fun renderVoices(p: SynthParams, out: FloatArray, n: Int) {
        val k = 0.03f + 0.97f * p.cutoff * p.cutoff
        val gateSamples = if (p.gateMs > 0) p.gateMs * SR / 1000 else Int.MAX_VALUE
        val dm = 2.0.pow(p.detune / 1200.0)
        val atkStep = 1f / (p.attack * SR); val decStep = (1f - p.sustain) / (p.decay * SR)
        for (v in voices) {
            var l = lfo
            for (i in 0 until n) {
                when (v.stage) {
                    0 -> { v.level += atkStep; if (v.level >= 1f) { v.level = 1f; v.stage = 1 } }
                    1 -> { v.level -= decStep; if (v.level <= p.sustain) { v.level = p.sustain; v.stage = 2 } }
                    3 -> { v.level -= v.relStep; if (v.level <= 0f) { v.level = 0f; v.stage = 4; break } }
                }
                if (++v.age > gateSamples && v.stage < 3) release(v, p)
                val vib = if (p.vibDepth > 0f) 2.0.pow(p.vibDepth * sin(l) / 1200.0) else 1.0
                l += 2 * PI * p.vibRate / SR
                val inc = v.freq * vib / SR
                v.ph += inc; if (v.ph >= 1.0) v.ph -= 1.0
                var x = wave(p.wave, v.ph)
                if (p.detune > 0f) { v.ph2 += inc * dm; if (v.ph2 >= 1.0) v.ph2 -= 1.0; x = (x + wave(p.wave, v.ph2)) * 0.5f }
                v.lp += k * (x - v.lp)
                out[i] += v.lp * v.level
            }
        }
        lfo += 2 * PI * p.vibRate / SR * n
        if (lfo > 1e6) lfo = 0.0
        voices.removeAll { it.stage == 4 }
    }

    private fun loop() {
        val n = 512
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf, n * 4)).setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val at = b.build()
        val f = FloatArray(n); val s = ShortArray(n); var idle = 0
        try {
            at.play()
            while (true) {
                val p = params
                java.util.Arrays.fill(f, 0f)
                var stop = false
                synchronized(lock) {
                    if (voices.isNotEmpty()) { idle = 0; renderVoices(p, f, n) }
                    else if (++idle > 260) { running = false; stop = true }
                }
                if (stop) break
                val g = p.volume * 0.3f
                for (i in 0 until n) s[i] = (max(-1f, min(1f, f[i] * g)) * 32000f).toInt().toShort()
                at.write(s, 0, n)
            }
        } finally { try { at.stop() } catch (e: Exception) { }; at.release(); synchronized(lock) { running = false } }
    }
}

/** Melody text format:  C4:300 F#3:150 [C4 E4 G4]:600 _:200   (note:ms, chord in brackets, _ = rest). */
object MelodyParser {
    class Step(val notes: List<Int>, val ms: Int)
    private val tok = Regex("(\\[[^\\]]*\\]|[A-Ga-g][#b]?-?\\d+|_)(?::(\\d+))?")
    private val semi = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun midi(s: String): Int? {
        val m = Regex("([A-Ga-g])([#b]?)(-?\\d+)").matchEntire(s.trim()) ?: return null
        var v = semi[m.groupValues[1][0].uppercaseChar()] ?: return null
        if (m.groupValues[2] == "#") v++ else if (m.groupValues[2] == "b") v--
        return v + (m.groupValues[3].toInt() + 1) * 12
    }

    fun parse(text: String): List<Step> = tok.findAll(text).mapNotNull { m ->
        val ms = m.groupValues[2].toIntOrNull()?.coerceIn(20, 20000) ?: 300
        val t = m.groupValues[1]
        when {
            t == "_" -> Step(emptyList(), ms)
            t.startsWith("[") -> t.trim('[', ']').split(Regex("\\s+")).mapNotNull { midi(it) }.let { if (it.isEmpty()) null else Step(it, ms) }
            else -> midi(t)?.let { Step(listOf(it), ms) }
        }
    }.toList()
}

class MelodyPlayer(private val synth: Synth) {
    @Volatile private var stopped = false
    fun stop() { stopped = true; synth.allOff() }

    fun play(steps: List<MelodyParser.Step>, speed: Double, done: () -> Unit) {
        Thread {
            try {
                for (st in steps) {
                    if (stopped) break
                    val ms = (st.ms / speed).toLong().coerceAtLeast(10)
                    st.notes.forEach { synth.noteOn(it, it) }
                    val hold = if (st.notes.isEmpty()) ms else (ms * 0.94).toLong()
                    Thread.sleep(hold)
                    st.notes.forEach { synth.noteOff(it) }
                    if (ms > hold) Thread.sleep(ms - hold)
                }
            } catch (e: InterruptedException) { } finally { synth.allOff(); done() }
        }.start()
    }
}
