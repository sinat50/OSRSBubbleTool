package com.sinat.osrsbubbletool

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

// The Zen Garden's raking sound: a bright, sandy hiss made on the spot (no sound file), louder the faster you
// rake. Its tone was first matched to a recording of real raked sand (the owner's tools/sandsound.mp3); in October
// 2026 the owner found that too heavy, then a brighter version too harsh, and picked this one from three
// candidates made on the PC with the same maths: the same kind of hiss, with less low "body" and the sizzle at
// the very top trimmed, plus a trace of grain (single grains catching, so it isn't perfectly even):
// - the rake: the low end cut below 3,300 Hz (was 1,400), the top softened from 9,000 Hz (was 14,800), a dip at
//   4,500 Hz; 4% of its sound below 3,000 Hz (was 10%)
// - the stick (a single point, so lighter than the rake's many teeth): cut below 4,500 Hz, softened from
//   10,500 Hz, a dip at 5,500 Hz, a little grainier
// That's the middle of the Tone slider (the volume menu; the owner liked Powerwash Simulator's): sliding it blends
// towards a softer sound (the top rolled off sooner, a little more body) or a sharper, brighter one (the first,
// brighter version, more grain), kept about as loud all the way along.
// It plays on its own thread only while you're raking, and lets go of the speaker about half a second after you
// stop.
class SandSound {

    companion object {
        private const val RATE = 44100      // samples a second (needed for the high part of the hiss)
        private const val CHUNK = 512       // samples made at a time (about 12 ms)
        // very quiet, in the background, even at full speed (halved in October 2026: the volume slider's Loud end
        // was too loud)
        private const val VOLUME = 0.0125f * 32767f
        // how quickly the loudness follows: it swells in over about 0.15 s and fades out over about 0.25 s,
        // so a stroke never starts or stops with a jump
        private const val SWELL = 1f / (0.15f * RATE)
        private const val FADE = 1f / (0.25f * RATE)

        const val RAKE = 0                  // the voices (see voice)
        const val STICK = 1
        // Each voice's tone at the Tone slider's soft end, middle and sharp end (see the top), 9 numbers each: its
        // low-end cut (Hz), its high-end softening (Hz), its three tone controls (Hz and how much they lift or dip,
        // in dB), and how grainy it is. (In between, the frequencies are blended evenly in pitch, the rest straight.)
        private val SOFT = arrayOf(
            doubleArrayOf(1900.0, 6000.0, 1700.0, 1.0, 3500.0, -3.0, 8000.0, 1.0, 0.05),
            doubleArrayOf(2700.0, 7500.0, 2600.0, 0.0, 4200.0, -2.5, 9000.0, 1.0, 0.12),
        )
        private val MIDDLE = arrayOf(
            doubleArrayOf(3300.0, 9000.0, 3000.0, 0.0, 4500.0, -3.0, 9000.0, 0.0, 0.05),
            doubleArrayOf(4500.0, 10500.0, 4000.0, 0.0, 5500.0, -3.0, 10000.0, 0.0, 0.12),
        )
        private val SHARP = arrayOf(
            doubleArrayOf(2300.0, 15500.0, 2800.0, 2.0, 4500.0, -3.0, 10000.0, 4.0, 0.10),
            doubleArrayOf(3600.0, 16000.0, 5000.0, 2.5, 7000.0, -2.0, 11500.0, 5.0, 0.25),
        )
        // How much louder each voice is made at 11 places along the slider, so it's about as loud all the way
        // along (cutting the low or high end takes some away; measured on the PC with the same maths)
        private val GAINS = arrayOf(
            floatArrayOf(2.67f, 2.67f, 2.68f, 2.70f, 2.73f, 2.79f, 2.28f, 1.89f, 1.59f, 1.35f, 1.17f),
            floatArrayOf(2.71f, 2.78f, 2.86f, 2.96f, 3.10f, 3.27f, 2.74f, 2.31f, 1.96f, 1.66f, 1.42f),
        )
        private val IS_FREQUENCY = booleanArrayOf(true, true, true, false, true, false, true, false, false)
        // the grains: about 500 a second, each fading over about 2.5 ms
        private const val GRAINS = (500.0 / RATE * 4294967296.0 - 2147483648.0).toInt()
        private val GRAIN_FADE = exp(-1.0 / (0.0025 * RATE)).toFloat()

        // How much a one-pole filter keeps of its last value, for a turning point of `freq` Hz
        private fun cut(freq: Double) = exp(-2 * PI * freq / RATE).toFloat()

        // One tone control (a "peaking" filter): b0, b1, b2, a1, a2
        private fun peak(freq: Double, gainDb: Double): FloatArray {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / RATE
            val alpha = sin(w) / (2 * 1.4)
            val a0 = 1 + alpha / a
            return floatArrayOf(((1 + alpha * a) / a0).toFloat(), (-2 * cos(w) / a0).toFloat(), ((1 - alpha * a) / a0).toFloat(),
                (-2 * cos(w) / a0).toFloat(), ((1 - alpha / a) / a0).toFloat())
        }
    }

    @Volatile private var target = 0f       // how loud it should be, 0 to 1
    @Volatile var volume = 1f               // the player's volume setting (1 = normal)
    @Volatile var voice = RAKE              // which tool it's the sound of: RAKE (also the brush) or STICK
    @Volatile var tone = 0.5f               // the Tone slider: 0 soft, 0.5 the usual, 1 sharp

    // The sound's settings for the voice and tone it was last set up for (worked out again when either changes)
    private var setVoice = -1
    private var setTone = -1f
    private var lowCut = 0f
    private var highCut = 0f
    private val toneControls = Array(3) { FloatArray(5) }
    private var grainy = 0f
    private var gain = 1f

    // Works out the sound's settings for voice v at tone t: between soft and middle, or middle and sharp
    private fun setUp(v: Int, t: Float) {
        setVoice = v; setTone = t
        val (a, b, u) = if (t < 0.5f) Triple(SOFT[v], MIDDLE[v], 2.0 * t) else Triple(MIDDLE[v], SHARP[v], 2.0 * t - 1)
        val x = DoubleArray(9) { i -> if (IS_FREQUENCY[i]) a[i] * (b[i] / a[i]).pow(u) else a[i] + (b[i] - a[i]) * u }
        lowCut = cut(x[0]); highCut = cut(x[1])
        for (k in 0 until 3) peak(x[2 + 2 * k], x[3 + 2 * k]).copyInto(toneControls[k])
        grainy = x[8].toFloat()
        val g = GAINS[v]
        val at = t.coerceIn(0f, 1f) * 10
        val i = at.toInt().coerceAtMost(9)
        gain = g[i] + (g[i + 1] - g[i]) * (at - i)
    }
    @Volatile private var running = false
    @Volatile private var stopNow = false

    // How loud to play: 0 = quiet (the sound then stops by itself)
    fun level(v: Float) {
        target = v
        if (v > 0f && !running) {
            running = true
            stopNow = false
            Thread({ play() }, "SandSound").start()
        }
    }

    // The window closed: stop straight away
    fun stop() {
        target = 0f
        stopNow = true
    }

    private fun play() {
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(max(minBuffer, CHUNK * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()
            val buffer = ShortArray(CHUNK)
            var seed = System.nanoTime().toInt() or 1
            var loud = 0f                               // follows `target` smoothly, so it never clicks
            var low1 = 0f; var low2 = 0f                // the low-end cut (taken away twice)
            var high1 = 0f; var high2 = 0f              // the high-end softening (twice)
            val x1 = FloatArray(3); val x2 = FloatArray(3)   // each tone control's last two inputs
            val y1 = FloatArray(3); val y2 = FloatArray(3)   // and last two outputs
            var quiet = 0
            var grain = 0f                              // the latest grain, fading
            var seed2 = (System.nanoTime() ushr 7).toInt() or 1
            while (!stopNow) {
                val goal = target
                val v = voice
                val t = tone
                if (v != setVoice || t != setTone) setUp(v, t)
                val lowCut = lowCut; val highCut = highCut; val tone = toneControls
                val grainy = grainy; val gain = gain
                for (i in 0 until CHUNK) {
                    loud += (goal - loud) * if (goal > loud) SWELL else FADE
                    seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                    var s = seed / 2.1474836E9f              // white noise, -1 to 1
                    low1 = lowCut * low1 + (1 - lowCut) * s; s -= low1
                    low2 = lowCut * low2 + (1 - lowCut) * s; s -= low2
                    high1 = highCut * high1 + (1 - highCut) * s; s = high1
                    high2 = highCut * high2 + (1 - highCut) * s; s = high2
                    for (k in 0 until 3) {
                        val c = tone[k]
                        val out = c[0] * s + c[1] * x1[k] + c[2] * x2[k] - c[3] * y1[k] - c[4] * y2[k]
                        x2[k] = x1[k]; x1[k] = s; y2[k] = y1[k]; y1[k] = out
                        s = out
                    }
                    // a grain now and then (a second jumble of numbers decides when, and how strongly)
                    seed2 = seed2 xor (seed2 shl 13); seed2 = seed2 xor (seed2 ushr 17); seed2 = seed2 xor (seed2 shl 5)
                    grain *= GRAIN_FADE
                    if (seed2 < GRAINS) grain = max(grain, 0.5f + 0.5f * ((seed2 ushr 8) and 255) / 255f)
                    s *= (1 - grainy) + grainy * 2.2f * grain
                    buffer[i] = (s * gain * loud * VOLUME * volume).coerceIn(-32000f, 32000f).toInt().toShort()
                }
                track.write(buffer, 0, CHUNK)
                // silent for a while: finish, so nothing runs while you're not raking
                if (goal == 0f && loud < 0.003f) { if (++quiet > 40) break } else quiet = 0
            }
        } catch (e: Exception) {
        } finally {
            try { track?.stop() } catch (e: Exception) { }
            track?.release()
            running = false
        }
    }
}
