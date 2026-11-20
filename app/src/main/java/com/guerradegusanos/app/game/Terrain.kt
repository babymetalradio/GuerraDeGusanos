package com.guerradegusanos.app.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Terreno destructible: un heightmap de un valor por columna de píxel.
 * `heights[x]` es la Y del suelo en esa columna (Y crece hacia abajo).
 *
 * Generación: 9 puntos de control aleatorios (extremos 330..420, resto
 * 300..460), interpolación coseno, más sin(x*0.011)*9 + sin(x*0.033+2)*4,
 * limitado a 210..520.
 */
class Terrain(val width: Int = 1280) {

    val heights: FloatArray = FloatArray(width)

    init {
        generate()
    }

    private fun generate(random: Random = Random.Default) {
        val controlCount = 9
        val values = FloatArray(controlCount)
        for (i in 0 until controlCount) {
            values[i] = if (i == 0 || i == controlCount - 1) {
                330f + random.nextFloat() * 90f // 330..420
            } else {
                300f + random.nextFloat() * 160f // 300..460
            }
        }
        for (x in 0 until width) {
            val fx = x.toFloat() / (width - 1).toFloat() * (controlCount - 1).toFloat()
            val i0 = min(floor(fx).toInt(), controlCount - 2)
            val t = fx - i0.toFloat()
            // Interpolación coseno entre los dos puntos de control.
            val mu = (1f - cos(PI.toFloat() * t)) / 2f
            var h = values[i0] * (1f - mu) + values[i0 + 1] * mu
            h += kotlin.math.sin(x * 0.011f) * 9f + kotlin.math.sin(x * 0.033f + 2f) * 4f
            heights[x] = h.coerceIn(210f, 520f)
        }
    }

    /** Altura del suelo en `x`, con interpolación lineal entre columnas. */
    fun groundAt(x: Float): Float {
        if (x <= 0f) return heights[0]
        if (x >= (width - 1).toFloat()) return heights[width - 1]
        val i = floor(x).toInt()
        val frac = x - i.toFloat()
        return heights[i] * (1f - frac) + heights[i + 1] * frac
    }

    /**
     * Excava un cráter circular centrado en (cx, cy) con el radio dado.
     * El suelo solo puede bajar: ground[x] = max(ground, bottom),
     * con el resultado limitado a 60..WATER_Y+8.
     */
    fun carve(cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return
        val x0 = max(0, floor(cx - radius).toInt())
        val x1 = min(width - 1, kotlin.math.ceil(cx + radius).toInt())
        for (xi in x0..x1) {
            val dx = xi.toFloat() - cx
            if (dx * dx > radius * radius) continue
            val bottom = cy + sqrt(radius * radius - dx * dx)
            heights[xi] = max(heights[xi], bottom).coerceIn(60f, WATER_Y + 8f)
        }
    }
}
