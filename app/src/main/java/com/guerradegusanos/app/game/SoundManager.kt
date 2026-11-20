package com.guerradegusanos.app.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sonidos sintetizados con AudioTrack: PCM generado en memoria, sin
 * assets, sin MediaPlayer y sin ToneGenerator. Sample rate 22050 Hz.
 *
 * Cada evento mezcla sus componentes en un solo buffer: un beep con
 * barrido de frecuencia (cuadrado, seno o diente de sierra) más ruido
 * cuando corresponde.
 */
class SoundManager(context: Context) {

    // Se guarda solo el contexto de aplicación; no se usa para nada más.
    @Suppress("unused")
    private val appContext: Context = context.applicationContext

    var enabled: Boolean = true

    private val sampleRate = 22050
    private var released = false

    fun play(event: SoundEvent) {
        if (!enabled || released) return
        try {
            val samples = render(event)
            if (samples.isEmpty()) return
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            val durationMs = (samples.size * 1000L / sampleRate) + 60L
            Thread {
                try {
                    Thread.sleep(durationMs)
                } catch (ignored: InterruptedException) {
                    // Interrumpido: liberar igualmente.
                }
                try {
                    track.stop()
                } catch (ignored: IllegalStateException) {
                    // Ya parado: no pasa nada.
                }
                track.release()
            }.start()
        } catch (ignored: Exception) {
            // Sin audio disponible: el juego sigue en silencio.
        }
    }

    fun release() {
        released = true
    }

    // ------------------------------------------------------------------
    // Síntesis
    // ------------------------------------------------------------------

    /** Genera el PCM (16 bits) de un evento. */
    private fun render(event: SoundEvent): ShortArray {
        val duration = when (event) {
            SoundEvent.SHOOT -> 0.22f
            SoundEvent.EXPLOSION -> 0.55f
            SoundEvent.HURT -> 0.12f
            SoundEvent.SPLASH -> 0.30f
            SoundEvent.CLICK -> 0.05f
            SoundEvent.JUMP -> 0.14f
        }
        val size = (duration * sampleRate).toInt()
        val buf = FloatArray(size)
        when (event) {
            SoundEvent.SHOOT -> {
                addSquare(buf, 520f, 120f, 0f, 0.22f, 0.5f)
                addNoise(buf, 0f, 0.15f, 0.35f, decay = false)
            }
            SoundEvent.EXPLOSION -> {
                addNoise(buf, 0f, 0.55f, 0.5f, decay = true)
                addSine(buf, 90f, 35f, 0f, 0.5f, 0.5f)
            }
            SoundEvent.HURT -> {
                addSawtooth(buf, 220f, 140f, 0f, 0.12f, 0.5f)
            }
            SoundEvent.SPLASH -> {
                addNoise(buf, 0f, 0.30f, 0.4f, decay = true)
                addSine(buf, 300f, 90f, 0f, 0.25f, 0.4f)
            }
            SoundEvent.CLICK -> {
                addSquare(buf, 700f, 700f, 0f, 0.05f, 0.4f)
            }
            SoundEvent.JUMP -> {
                addSquare(buf, 300f, 560f, 0f, 0.14f, 0.45f)
            }
        }
        val out = ShortArray(size)
        for (i in 0 until size) {
            val v = buf[i].coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
        }
        return out
    }

    /** Fase acumulada con barrido lineal de frecuencia f0 -> f1. */
    private fun addTone(
        buf: FloatArray,
        f0: Float,
        f1: Float,
        startSec: Float,
        durSec: Float,
        amp: Float,
        wave: (phase: Float) -> Float
    ) {
        val start = (startSec * sampleRate).toInt()
        val count = (durSec * sampleRate).toInt()
        var phase = 0f
        for (i in 0 until count) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toFloat() / count.toFloat()
            val freq = f0 + (f1 - f0) * t
            phase += 2f * PI.toFloat() * freq / sampleRate.toFloat()
            buf[idx] += wave(phase) * amp
        }
    }

    private fun addSquare(buf: FloatArray, f0: Float, f1: Float, startSec: Float, durSec: Float, amp: Float) {
        addTone(buf, f0, f1, startSec, durSec, amp) { phase ->
            if (sin(phase) >= 0f) 1f else -1f
        }
    }

    private fun addSine(buf: FloatArray, f0: Float, f1: Float, startSec: Float, durSec: Float, amp: Float) {
        addTone(buf, f0, f1, startSec, durSec, amp) { phase -> sin(phase) }
    }

    private fun addSawtooth(buf: FloatArray, f0: Float, f1: Float, startSec: Float, durSec: Float, amp: Float) {
        addTone(buf, f0, f1, startSec, durSec, amp) { phase ->
            val cycles = phase / (2f * PI.toFloat())
            2f * (cycles - kotlin.math.floor(cycles)) - 1f
        }
    }

    private fun addNoise(buf: FloatArray, startSec: Float, durSec: Float, amp: Float, decay: Boolean) {
        val start = (startSec * sampleRate).toInt()
        val count = (durSec * sampleRate).toInt()
        val random = Random.Default
        for (i in 0 until count) {
            val idx = start + i
            if (idx >= buf.size) break
            val env = if (decay) 1f - i.toFloat() / count.toFloat() else 1f
            buf[idx] += (random.nextFloat() * 2f - 1f) * amp * env
        }
    }
}
