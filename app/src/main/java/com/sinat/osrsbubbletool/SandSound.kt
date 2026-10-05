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
// rake. Its tone was matched to a recording of real raked sand (the owner's tools/sandsound.mp3): mostly high
// hiss from 1,500 to 14,000 Hz, a little dip around 3,500 Hz. It plays on its own thread only while you're
// raking, and lets go of the speaker about half a second after you stop.
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

        // The tone controls fitted to the recording: cut the low end below 1,400 Hz, soften above 14,800 Hz,
        // then lift 1,500 Hz by 5.9 dB, dip 3,500 Hz by 4 dB and lift 9,000 Hz by 3.2 dB
        private val LOW_CUT = exp(-2 * PI * 1400 / RATE).toFloat()
        private val HIGH_CUT = exp(-2 * PI * 14800 / RATE).toFloat()
        private val TONE = arrayOf(peak(1500.0, 5.9), peak(3500.0, -4.0), peak(9000.0, 3.2))

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
            while (!stopNow) {
                val goal = target
                for (i in 0 until CHUNK) {
                    loud += (goal - loud) * if (goal > loud) SWELL else FADE
                    seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                    var s = seed / 2.1474836E9f              // white noise, -1 to 1
                    low1 = LOW_CUT * low1 + (1 - LOW_CUT) * s; s -= low1
                    low2 = LOW_CUT * low2 + (1 - LOW_CUT) * s; s -= low2
                    high1 = HIGH_CUT * high1 + (1 - HIGH_CUT) * s; s = high1
                    high2 = HIGH_CUT * high2 + (1 - HIGH_CUT) * s; s = high2
                    for (k in 0 until 3) {
                        val c = TONE[k]
                        val out = c[0] * s + c[1] * x1[k] + c[2] * x2[k] - c[3] * y1[k] - c[4] * y2[k]
                        x2[k] = x1[k]; x1[k] = s; y2[k] = y1[k]; y1[k] = out
                        s = out
                    }
                    buffer[i] = (s * loud * VOLUME * volume).coerceIn(-32000f, 32000f).toInt().toShort()
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
