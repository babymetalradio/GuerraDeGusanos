package com.guerradegusanos.app.game

/** Dimensiones del espacio lógico del mundo (la vista escala antes de dibujar). */
const val WORLD_WIDTH: Float = 1280f
const val WORLD_HEIGHT: Float = 640f

/** Altura Y del agua: por debajo de aquí un gusano se ahoga. */
const val WATER_Y: Float = 624f

/** Modo de partida. */
enum class GameMode { VS_CPU, TWO_PLAYERS }

/** Dificultad de la CPU (solo aplica en VS_CPU). */
enum class Difficulty { EASY, NORMAL, HARD }

/** Configuración de una partida. */
data class GameConfig(
    val mode: GameMode = GameMode.VS_CPU,
    val wormsPerTeam: Int = 3,
    val difficulty: Difficulty = Difficulty.NORMAL
)

/** Equipos. */
enum class Team { RED, BLUE }

/** Armas disponibles (sin munición). */
enum class Weapon { BAZOOKA, GRENADE, DYNAMITE }

/** Eventos de sonido que el motor notifica a la UI. */
enum class SoundEvent { SHOOT, EXPLOSION, HURT, SPLASH, CLICK, JUMP }

/** Estados del motor. */
enum class GameState { AIM, CPU_THINK, CPU_AIM, PROJECTILE, RETREAT, SETTLE, OVER }
