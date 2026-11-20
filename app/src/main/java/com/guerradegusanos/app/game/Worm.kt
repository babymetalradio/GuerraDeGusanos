package com.guerradegusanos.app.game

/**
 * Un gusano. `x`/`y` es la posición de sus pies sobre el terreno
 * (`y` == groundAt(x) cuando está en el suelo).
 */
class Worm(val team: Team, val name: String, var x: Float, var y: Float) {
    var vx: Float = 0f
    var vy: Float = 0f
    var hp: Int = 100
    var alive: Boolean = true
    var facing: Int = 1
    var onGround: Boolean = true
}
