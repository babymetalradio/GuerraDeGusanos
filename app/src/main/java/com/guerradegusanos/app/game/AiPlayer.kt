package com.guerradegusanos.app.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Plan que la IA decide para su turno: ángulo, potencia y arma. */
data class AiPlan(val angleDeg: Float, val power: Float, val weapon: Weapon)

/**
 * IA por simulación.
 *
 * - Objetivo: el enemigo con menos HP (el 35% de las veces uno aleatorio
 *   entre los vivos).
 * - Arma: 78% bazooka / 22% granada.
 * - Busca ángulo 12..168° (paso 3°) y potencia 12..100 (paso 4) simulando
 *   hasta 210 pasos con la misma física del motor. La bazooka puntúa la
 *   distancia mínima a la trayectoria; la granada puntúa
 *   min(mejor*0.6, distancia final).
 * - Al mejor plan se le añade error según la dificultad y luego se limita
 *   el ángulo a 5..175° y la potencia a 8..100.
 */
class AiPlayer(private val random: Random = Random.Default) {

    fun plan(
        shooter: Worm,
        enemies: List<Worm>,
        terrain: Terrain,
        wind: Int,
        difficulty: Difficulty
    ): AiPlan {
        val aliveEnemies = enemies.filter { it.alive }
        if (aliveEnemies.isEmpty()) {
            return AiPlan(55f, 55f, Weapon.BAZOOKA)
        }
        val target = if (random.nextFloat() < 0.35f) {
            aliveEnemies[random.nextInt(aliveEnemies.size)]
        } else {
            aliveEnemies.minByOrNull { it.hp } ?: aliveEnemies[0]
        }
        val weapon = if (random.nextFloat() < 0.78f) Weapon.BAZOOKA else Weapon.GRENADE

        var bestScore = Float.MAX_VALUE
        var bestAngle = 90f
        var bestPower = 50f
        var angle = 12
        while (angle <= 168) {
            var power = 12
            while (power <= 100) {
                val score = simulate(shooter, target, terrain, wind, weapon, angle.toFloat(), power.toFloat())
                if (score < bestScore) {
                    bestScore = score
                    bestAngle = angle.toFloat()
                    bestPower = power.toFloat()
                }
                power += 4
            }
            angle += 3
        }

        val (angleErr, powerErr) = when (difficulty) {
            Difficulty.EASY -> 7f to 13f
            Difficulty.NORMAL -> 3.6f to 7f
            Difficulty.HARD -> 1.6f to 3.5f
        }
        val finalAngle = (bestAngle + (random.nextFloat() * 2f - 1f) * angleErr)
            .coerceIn(5f, 175f)
        val finalPower = (bestPower + (random.nextFloat() * 2f - 1f) * powerErr)
            .coerceIn(8f, 100f)
        return AiPlan(finalAngle, finalPower, weapon)
    }

    /**
     * Simula un disparo y devuelve su puntuación (menor es mejor).
     * Reproduce la física del motor: gravedad 0.22, viento 0.0075
     * (bazooka) o 0.003 (granada), rebote de granada y mecha de 190 ticks.
     */
    private fun simulate(
        shooter: Worm,
        target: Worm,
        terrain: Terrain,
        wind: Int,
        weapon: Weapon,
        angleDeg: Float,
        power: Float
    ): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        var x = shooter.x + cos(rad).toFloat() * 24f
        var y = (shooter.y - 14f) - sin(rad).toFloat() * 24f
        val speed = 4.5f + power * 0.135f
        var vx = cos(rad).toFloat() * speed
        var vy = -sin(rad).toFloat() * speed
        val windFactor = if (weapon == Weapon.BAZOOKA) 0.0075f else 0.003f
        val tx = target.x
        val ty = target.y - 12f

        var best = Float.MAX_VALUE
        var step = 0
        while (step < 210) {
            vy += 0.22f
            vx += wind * windFactor
            x += vx
            y += vy
            step++
            val d = hypot((x - tx).toDouble(), (y - ty).toDouble()).toFloat()
            if (d < best) best = d
            if (weapon == Weapon.BAZOOKA) {
                if (y >= terrain.groundAt(x) || y >= WATER_Y || x < -30f || x > 1310f) break
            } else {
                if (y >= WATER_Y || x < -30f || x > 1310f) {
                    // Cae al agua o sale de la pantalla: no explota.
                    return minOf(best * 0.6f, d)
                }
                if (y >= terrain.groundAt(x)) {
                    y = terrain.groundAt(x)
                    vy = -vy * 0.42f
                    vx *= 0.7f
                    if (abs(vy) < 1.4f) {
                        vy = 0f
                        vx = 0f
                    }
                }
                if (step >= 190) {
                    // Mecha agotada: explota donde esté.
                    val finalDist = hypot((x - tx).toDouble(), (y - ty).toDouble()).toFloat()
                    return minOf(best * 0.6f, finalDist)
                }
            }
        }
        return if (weapon == Weapon.GRENADE) {
            val finalDist = hypot((x - tx).toDouble(), (y - ty).toDouble()).toFloat()
            minOf(best * 0.6f, finalDist)
        } else {
            best
        }
    }
}
