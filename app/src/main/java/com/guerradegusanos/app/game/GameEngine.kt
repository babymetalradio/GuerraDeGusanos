package com.guerradegusanos.app.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Datos internos
// ---------------------------------------------------------------------------

private class Projectile(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val weapon: Weapon,
    var fuse: Int = 0,
    var age: Int = 0
)

private class PlacedDynamite(var x: Float, var y: Float, var fuse: Int)

private class FloatingText(
    var x: Float,
    var y: Float,
    val text: String,
    var life: Int,
    val color: Int
)

private class Cloud(val x: Float, val y: Float, val s: Float)
private class Bird(val x: Float, val y: Float, val s: Float)
private class Deco(val x: Float, val kind: Int, val s: Float)

// ---------------------------------------------------------------------------
// Motor
// ---------------------------------------------------------------------------

/**
 * Motor del juego "Guerra de Gusanos". Corre a 60 ticks/s (la vista llama
 * a [update] en cada tick) en el espacio lógico 1280x640.
 *
 * Rarezas preservadas de la versión web: el temporizador solo corre en
 * estado AIM, la preview de granada se corta al primer contacto con el
 * suelo (sin rebote), la CPU no se mueve, no salta ni usa dinamita, no
 * hay munición, ni persistencia, ni puntuación.
 *
 * `config`, `worms` y `terrain` se exponen como `val` públicos con un
 * respaldo mutable interno, para que [newGame] pueda reiniciar la
 * partida sin romper el contrato público.
 */
class GameEngine(initialConfig: GameConfig) {

    // ------------------------------------------------------------------
    // Estado público (contrato)
    // ------------------------------------------------------------------

    private var configInternal: GameConfig = initialConfig
    val config: GameConfig get() = configInternal

    private val wormsInternal = mutableListOf<Worm>()
    val worms: List<Worm> get() = wormsInternal

    private var terrainInternal: Terrain = Terrain()
    val terrain: Terrain get() = terrainInternal

    var wind: Int = 0
    var state: GameState = GameState.AIM
    var currentWorm: Worm? = null
    var angleDeg: Float = 55f
    var power: Float = 0f
    var weapon: Weapon = Weapon.BAZOOKA
    var timeLeftTicks: Int = 45 * 60
    var winner: Team? = null
    var isDraw: Boolean = false
    var statusMessage: String = ""

    /** Sacudida de pantalla por explosiones; decae en cada tick. */
    var shake: Float = 0f

    /** Potencia fijada por arrastre (dragAim) o por adjustAim; null si no hay. */
    internal var dragPower: Float? = null

    var onSound: ((SoundEvent) -> Unit)? = null
    var onVibrate: ((LongArray) -> Unit)? = null

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    private var currentTeam: Team = Team.RED
    private val teamPointer = mutableMapOf(Team.RED to 0, Team.BLUE to 0)

    private var charging: Boolean = false
    private var chargeDir: Float = 1f

    private var projectile: Projectile? = null
    private var dynamite: PlacedDynamite? = null
    private var retreatTicks: Int = 0
    private var settleTicks: Int = 0
    private var cpuThinkTicks: Int = 0
    private var cpuAimTicks: Int = 0

    private val aiPlayer = AiPlayer()
    private var aiPlan: AiPlan? = null

    private val floatingTexts = mutableListOf<FloatingText>()

    // Decoración (solo visual, generada con semilla fija).
    private val clouds = mutableListOf<Cloud>()
    private val birds = mutableListOf<Bird>()
    private val decos = mutableListOf<Deco>()
    private var drawFrame: Long = 0

    // ------------------------------------------------------------------
    // Constantes
    // ------------------------------------------------------------------

    companion object {
        private const val TURN_SECONDS = 45
        private const val TICKS_PER_SECOND = 60
        private const val RETREAT_TICKS = 6 * 60
        private const val GRENADE_FUSE = 190
        private const val DYNAMITE_FUSE = 300
        private const val CPU_THINK_TICKS = 55
        private const val CPU_AIM_MIN_TICKS = 42
        private const val CPU_AIM_FORCE_TICKS = 200
        private const val SETTLE_STABLE_TICKS = 35
        private const val WORM_GRAVITY = 0.38f
        private const val WALK_SPEED = 1.7f
        private const val JUMP_VY = -8.6f

        private val RED_NAMES = listOf("Ramón", "Lombriz", "Tito", "Chispa")
        private val BLUE_NAMES = listOf("Sargento", "Bicho", "Nube", "Rex")

        private val RED_COLOR: Int = 0xFFE2483D.toInt()
        private val RED_DARK: Int = 0xFFA02C23.toInt()
        private val BLUE_COLOR: Int = 0xFF4A90E2.toInt()
        private val BLUE_DARK: Int = 0xFF2B5EA8.toInt()
    }

    init {
        newGame(configInternal)
    }

    // ------------------------------------------------------------------
    // Pinturas (reutilizadas en cada draw)
    // ------------------------------------------------------------------

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, WORLD_HEIGHT,
            intArrayOf(0xFF5FB2F0.toInt(), 0xFFA8DCF7.toInt(), 0xFFD8F0E4.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD93B.toInt() }
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFD93B.toInt()
    }
    private val mountainFarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FB4D4.toInt()
    }
    private val mountainNearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6FA85C.toInt()
    }
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
    }
    private val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val terrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A5A33.toInt()
        style = Paint.Style.FILL
    }
    private val terrainVeinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E4525.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF58A447.toInt()
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val grassLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7CC46A.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, WATER_Y, 0f, WORLD_HEIGHT,
            intArrayOf(0xFF2F7FC9.toInt(), 0xFF1B4F8A.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
    }
    private val waterLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val treeTrunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E4525.toInt()
    }
    private val treeLeafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E8E41.toInt()
    }
    private val rockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9A9A.toInt()
    }
    private val flowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7BAC.toInt()
    }
    private val bushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9A3F.toInt()
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val previewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }
    private val projectilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
    }
    private val grenadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt()
    }
    private val dynamitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC62828.toInt()
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
    }
    private val wormOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val wormFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eyeBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FF9E9E.toInt()
    }
    private val helmetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFD8DC.toInt()
    }
    private val helmetDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF78909C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val nameBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nameTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val floatOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val floatFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    // ------------------------------------------------------------------
    // Partida y turnos
    // ------------------------------------------------------------------

    fun newGame(config: GameConfig) {
        configInternal = config
        terrainInternal = Terrain()
        wormsInternal.clear()
        floatingTexts.clear()
        projectile = null
        dynamite = null
        charging = false
        dragPower = null
        power = 0f
        weapon = Weapon.BAZOOKA
        angleDeg = 55f
        wind = 0
        timeLeftTicks = TURN_SECONDS * TICKS_PER_SECOND
        winner = null
        isDraw = false
        shake = 0f
        settleTicks = 0
        retreatTicks = 0
        cpuThinkTicks = 0
        cpuAimTicks = 0
        aiPlan = null
        currentWorm = null
        teamPointer[Team.RED] = 0
        teamPointer[Team.BLUE] = 0
        statusMessage = ""

        val count = config.wormsPerTeam.coerceIn(1, 4)
        for (i in 0 until count) {
            val rx = 90f + i * 115f + (Random.Default.nextFloat() * 36f - 18f)
            wormsInternal.add(
                Worm(Team.RED, RED_NAMES[i], rx, terrainInternal.groundAt(rx)).apply {
                    facing = 1
                }
            )
            val bx = WORLD_WIDTH - 90f - i * 115f + (Random.Default.nextFloat() * 36f - 18f)
            wormsInternal.add(
                Worm(Team.BLUE, BLUE_NAMES[i], bx, terrainInternal.groundAt(bx)).apply {
                    facing = -1
                }
            )
        }

        generateDecor()
        // Primer equipo aleatorio 50/50.
        currentTeam = if (Random.Default.nextBoolean()) Team.RED else Team.BLUE
        startTurn()
    }

    private fun generateDecor() {
        val r = Random(1234)
        clouds.clear()
        repeat(6) {
            clouds.add(Cloud(r.nextFloat() * WORLD_WIDTH, 30f + r.nextFloat() * 110f, 0.7f + r.nextFloat() * 0.7f))
        }
        birds.clear()
        repeat(5) {
            birds.add(Bird(r.nextFloat() * WORLD_WIDTH, 60f + r.nextFloat() * 120f, 0.7f + r.nextFloat() * 0.6f))
        }
        decos.clear()
        repeat(26) {
            val x = 20f + r.nextFloat() * (WORLD_WIDTH - 40f)
            val kind = when (r.nextFloat()) {
                in 0f..0.3f -> 0 // arbolito
                in 0.3f..0.5f -> 1 // roca
                in 0.5f..0.75f -> 2 // flor
                else -> 3 // mata
            }
            decos.add(Deco(x, kind, 0.7f + r.nextFloat() * 0.6f))
        }
    }

    private fun teamName(team: Team): String = if (team == Team.RED) "Rojo" else "Azul"

    /** Comprueba la victoria por conteo de vivos. Devuelve true si terminó. */
    private fun checkVictory(): Boolean {
        val redAlive = wormsInternal.count { it.team == Team.RED && it.alive }
        val blueAlive = wormsInternal.count { it.team == Team.BLUE && it.alive }
        if (redAlive == 0 && blueAlive == 0) {
            isDraw = true
            winner = null
            state = GameState.OVER
            statusMessage = "¡Empate!"
            return true
        }
        if (redAlive == 0) {
            winner = Team.BLUE
            isDraw = false
            state = GameState.OVER
            statusMessage = "¡Gana el Equipo Azul!"
            return true
        }
        if (blueAlive == 0) {
            winner = Team.RED
            isDraw = false
            state = GameState.OVER
            statusMessage = "¡Gana el Equipo Rojo!"
            return true
        }
        return false
    }

    private fun startTurn() {
        if (checkVictory()) return
        val teamWorms = wormsInternal.filter { it.team == currentTeam }
        if (teamWorms.none { it.alive }) {
            checkVictory()
            return
        }
        // Rota el gusano activo por equipo con puntero módulo, saltando muertos.
        var idx = (teamPointer.getValue(currentTeam)) % teamWorms.size
        var guard = 0
        while (!teamWorms[idx].alive && guard <= teamWorms.size) {
            idx = (idx + 1) % teamWorms.size
            guard++
        }
        val worm = teamWorms[idx]
        teamPointer[currentTeam] = (idx + 1) % teamWorms.size
        currentWorm = worm

        wind = Random.Default.nextInt(-5, 6) // -5..5
        timeLeftTicks = TURN_SECONDS * TICKS_PER_SECOND
        weapon = Weapon.BAZOOKA
        power = 0f
        dragPower = null
        charging = false
        angleDeg = if (currentTeam == Team.RED) 55f else 125f
        projectile = null
        dynamite = null
        settleTicks = 0
        retreatTicks = 0
        statusMessage = "Turno de ${worm.name} · Equipo ${teamName(currentTeam)}"

        val isCpuTurn = configInternal.mode == GameMode.VS_CPU && currentTeam == Team.BLUE
        if (isCpuTurn) {
            val enemies = wormsInternal.filter { it.team != currentTeam }
            aiPlan = aiPlayer.plan(worm, enemies, terrainInternal, wind, configInternal.difficulty)
            cpuThinkTicks = 0
            cpuAimTicks = 0
            state = GameState.CPU_THINK
        } else {
            aiPlan = null
            state = GameState.AIM
        }
    }

    private fun endTurn() {
        if (checkVictory()) return
        currentTeam = if (currentTeam == Team.RED) Team.BLUE else Team.RED
        startTurn()
    }

    // ------------------------------------------------------------------
    // Bucle principal (60 ticks/s)
    // ------------------------------------------------------------------

    fun update() {
        updateFloatingTexts()
        if (shake > 0.2f) {
            shake *= 0.92f
        } else {
            shake = 0f
        }
        when (state) {
            GameState.AIM -> {
                // El temporizador SOLO decrece en estado AIM.
                if (timeLeftTicks > 0) {
                    timeLeftTicks--
                    if (timeLeftTicks == 0) {
                        statusMessage = "¡Se acabó el tiempo!"
                        charging = false
                        endTurn()
                        return
                    }
                }
                if (charging) {
                    power += chargeDir * 1.7f
                    if (power >= 100f) {
                        power = 100f
                        chargeDir = -1f
                    } else if (power <= 0f) {
                        power = 0f
                        chargeDir = 1f
                    }
                }
                updateWorms()
            }
            GameState.CPU_THINK -> {
                cpuThinkTicks++
                if (cpuThinkTicks >= CPU_THINK_TICKS) {
                    cpuAimTicks = 0
                    state = GameState.CPU_AIM
                }
            }
            GameState.CPU_AIM -> {
                cpuAimTicks++
                val plan = aiPlan
                if (plan != null) {
                    val diff = plan.angleDeg - angleDeg
                    val step = diff.coerceIn(-2.2f, 2.2f)
                    angleDeg += step
                    if ((abs(diff) < 2.4f && cpuAimTicks > CPU_AIM_MIN_TICKS) ||
                        cpuAimTicks >= CPU_AIM_FORCE_TICKS
                    ) {
                        angleDeg = plan.angleDeg
                        fireCpu(plan)
                    }
                } else {
                    // Sin plan: no quedarse atascado.
                    endTurn()
                    return
                }
                updateWorms()
            }
            GameState.PROJECTILE -> {
                updateProjectile()
                updateWorms()
            }
            GameState.RETREAT -> {
                retreatTicks--
                val d = dynamite
                if (d != null) {
                    d.fuse--
                    if (d.fuse <= 0) {
                        dynamite = null
                        explode(d.x, d.y, Weapon.DYNAMITE)
                        state = GameState.SETTLE
                        settleTicks = 0
                    }
                } else if (retreatTicks <= 0) {
                    state = GameState.SETTLE
                    settleTicks = 0
                }
                if (retreatTicks <= 0 && dynamite != null) {
                    // Seguridad: la retirada no dura más de 6 s.
                    val dd = dynamite
                    dynamite = null
                    if (dd != null) explode(dd.x, dd.y, Weapon.DYNAMITE)
                    state = GameState.SETTLE
                    settleTicks = 0
                }
                updateWorms()
            }
            GameState.SETTLE -> {
                updateWorms()
                if (projectile == null && dynamite == null) {
                    val stable = wormsInternal.filter { it.alive }.all { it.onGround }
                    if (stable) {
                        settleTicks++
                        if (settleTicks > SETTLE_STABLE_TICKS) {
                            settleTicks = 0
                            endTurn()
                            return
                        }
                    } else {
                        settleTicks = 0
                    }
                } else {
                    settleTicks = 0
                }
            }
            GameState.OVER -> {
                // Nada más que hacer.
            }
        }
    }

    private fun fireCpu(plan: AiPlan) {
        weapon = plan.weapon
        fire(plan.power)
    }

    private fun toSettle() {
        projectile = null
        state = GameState.SETTLE
        settleTicks = 0
    }

    // ------------------------------------------------------------------
    // Proyectiles
    // ------------------------------------------------------------------

    private fun updateProjectile() {
        val p = projectile ?: run {
            toSettle()
            return
        }
        p.age++
        when (p.weapon) {
            Weapon.BAZOOKA -> {
                p.vy += 0.22f
                p.vx += wind * 0.0075f
                p.x += p.vx
                p.y += p.vy
                if (p.y >= WATER_Y || p.x < -30f || p.x > 1310f) {
                    // Cae al agua o sale de la pantalla: desaparece sin explotar.
                    toSettle()
                    return
                }
                if (p.y >= terrainInternal.groundAt(p.x)) {
                    explode(p.x, p.y, Weapon.BAZOOKA)
                    toSettle()
                    return
                }
                for (worm in wormsInternal) {
                    if (!worm.alive) continue
                    if (p.age > 8 &&
                        abs(p.x - worm.x) < 14f &&
                        abs(p.y - (worm.y - 12f)) < 15f
                    ) {
                        explode(p.x, p.y, Weapon.BAZOOKA)
                        toSettle()
                        return
                    }
                }
            }
            Weapon.GRENADE -> {
                p.vy += 0.22f
                p.vx += wind * 0.003f
                p.x += p.vx
                p.y += p.vy
                p.fuse--
                if (p.y >= WATER_Y || p.x < -30f || p.x > 1310f) {
                    toSettle()
                    return
                }
                if (p.y >= terrainInternal.groundAt(p.x)) {
                    p.y = terrainInternal.groundAt(p.x)
                    p.vy = -p.vy * 0.42f
                    p.vx *= 0.7f
                    if (abs(p.vy) < 1.4f) {
                        p.vy = 0f
                        p.vx = 0f
                    }
                }
                if (p.fuse <= 0) {
                    explode(p.x, p.y, Weapon.GRENADE)
                    toSettle()
                    return
                }
            }
            Weapon.DYNAMITE -> {
                // La dinamita nunca vuela: se coloca y tiene su propio estado.
                toSettle()
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // Explosiones y daño
    // ------------------------------------------------------------------

    private fun weaponRadius(w: Weapon): Float = when (w) {
        Weapon.BAZOOKA -> 46f
        Weapon.GRENADE -> 52f
        Weapon.DYNAMITE -> 62f
    }

    private fun weaponDamage(w: Weapon): Int = when (w) {
        Weapon.BAZOOKA -> 48
        Weapon.GRENADE -> 62
        Weapon.DYNAMITE -> 74
    }

    private fun explode(x: Float, y: Float, w: Weapon) {
        val radius = weaponRadius(w)
        val baseDamage = weaponDamage(w)
        terrainInternal.carve(x, y, radius)
        shake = min(16f, radius / 3.4f)
        onSound?.invoke(SoundEvent.EXPLOSION)
        onVibrate?.invoke(longArrayOf(60, 40, 80))
        val range = radius + 34f
        var anyDamage = false
        for (worm in wormsInternal) {
            if (!worm.alive) continue
            val dx = worm.x - x
            val dy = (worm.y - 12f) - y
            val d = sqrt(dx * dx + dy * dy)
            if (d < range) {
                val f = 1f - d / range
                if (d > 0.001f) {
                    worm.vx += (dx / d) * f * 10.5f
                    worm.vy += (dy / d) * f * 10.5f - 3.2f
                } else {
                    worm.vy += -3.2f
                }
                if (abs(worm.vx) > 0.6f || worm.vy < -0.6f) {
                    worm.onGround = false
                }
                val dmg = (baseDamage * f).roundToInt()
                applyDamage(worm, dmg, silent = true)
                anyDamage = true
            }
        }
        if (anyDamage) onSound?.invoke(SoundEvent.HURT)
    }

    private fun applyDamage(worm: Worm, dmg: Int, silent: Boolean = false) {
        if (!worm.alive || dmg <= 0) return
        worm.hp -= dmg
        addFloatingText(worm.x, worm.y - 34f, "-$dmg", Color.WHITE)
        if (worm.hp <= 0) {
            worm.hp = 0
            worm.alive = false
            addFloatingText(worm.x, worm.y - 56f, "¡K.O.!", 0xFFFF5252.toInt())
        }
        if (!silent) onSound?.invoke(SoundEvent.HURT)
    }

    private fun addFloatingText(x: Float, y: Float, text: String, color: Int) {
        floatingTexts.add(FloatingText(x, y, text, 90, color))
    }

    private fun updateFloatingTexts() {
        val it = floatingTexts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.life--
            t.y -= 0.5f
            if (t.life <= 0) it.remove()
        }
    }

    // ------------------------------------------------------------------
    // Física de gusanos
    // ------------------------------------------------------------------

    private fun updateWorms() {
        for (worm in wormsInternal) {
            if (!worm.alive) continue
            if (!worm.onGround) {
                worm.vy += WORM_GRAVITY
                worm.x += worm.vx
                worm.y += worm.vy
                if (worm.x < 8f) {
                    worm.x = 8f
                    worm.vx = 0f
                } else if (worm.x > WORLD_WIDTH - 8f) {
                    worm.x = WORLD_WIDTH - 8f
                    worm.vx = 0f
                }
                val ground = terrainInternal.groundAt(worm.x)
                if (worm.y >= ground) {
                    val impactVy = worm.vy
                    worm.y = ground
                    worm.vy = 0f
                    worm.vx = 0f
                    worm.onGround = true
                    if (impactVy > 11.5f) {
                        val dmg = ((impactVy - 11.5f) * 4.5f).roundToInt()
                        applyDamage(worm, dmg)
                    }
                }
            } else {
                // En el suelo: seguir el terreno; si se excavó bajo sus
                // pies, empieza a caer.
                val ground = terrainInternal.groundAt(worm.x)
                if (ground > worm.y + 0.5f) {
                    worm.onGround = false
                    worm.vy = 0f
                } else {
                    worm.y = ground
                }
            }
            // Ahogamiento.
            if (worm.alive && worm.y - 4f > WATER_Y) {
                worm.hp = 0
                worm.alive = false
                worm.vx = 0f
                worm.vy = 0f
                addFloatingText(worm.x, worm.y - 34f, "¡Se ahogó!", 0xFF4FC3F7.toInt())
                onSound?.invoke(SoundEvent.SPLASH)
            }
        }
    }

    // ------------------------------------------------------------------
    // Acciones del jugador
    // ------------------------------------------------------------------

    fun move(dir: Int) {
        if (state != GameState.AIM && state != GameState.RETREAT) return
        if (dir != -1 && dir != 1) return
        val worm = currentWorm ?: return
        if (!worm.alive) return
        worm.facing = dir
        val nx = worm.x + dir * WALK_SPEED
        if (nx < 8f || nx > WORLD_WIDTH - 8f) return
        val groundAhead = terrainInternal.groundAt(nx)
        // Solo avanza si el suelo de delante no sube más de 10 px.
        if (groundAhead < worm.y - 10f) return
        worm.x = nx
        if (worm.onGround) {
            if (groundAhead > worm.y + 3f) {
                // El suelo cae: empieza a caer.
                worm.onGround = false
                worm.vy = 0f
            } else {
                worm.y = groundAhead
            }
        }
        // Al moverse, el ángulo se espeja.
        if (dir == 1 && angleDeg > 90f) {
            angleDeg = 180f - angleDeg
        } else if (dir == -1 && angleDeg < 90f) {
            angleDeg = 180f - angleDeg
        }
    }

    fun jump() {
        if (state != GameState.AIM && state != GameState.RETREAT) return
        val worm = currentWorm ?: return
        if (!worm.alive || !worm.onGround) return
        worm.vy = JUMP_VY
        worm.vx = worm.facing * 1.6f
        worm.onGround = false
        onSound?.invoke(SoundEvent.JUMP)
    }

    fun adjustAim(deltaDeg: Float) {
        if (state != GameState.AIM) return
        angleDeg = (angleDeg + deltaDeg).coerceIn(3f, 177f)
        if (dragPower == null) dragPower = 55f
    }

    fun selectWeapon(w: Weapon) {
        if (state != GameState.AIM) return
        weapon = w
    }

    fun startCharging() {
        if (state != GameState.AIM) return
        val drag = dragPower
        if (drag != null) {
            // Con potencia fijada por arrastre se dispara de inmediato.
            fire(drag)
            return
        }
        charging = true
        chargeDir = 1f
        power = 0f
    }

    fun releaseFire() {
        if (state != GameState.AIM) return
        val drag = dragPower
        if (drag != null) {
            fire(drag)
            return
        }
        if (charging) {
            fire(power)
        }
    }

    fun dragAim(angleDeg: Float, power: Float) {
        this.angleDeg = angleDeg.coerceIn(3f, 177f)
        dragPower = power.coerceIn(5f, 100f)
    }

    // ------------------------------------------------------------------
    // Disparo
    // ------------------------------------------------------------------

    /** Boca del arma: a 24 px del centro (y-14) según el ángulo. */
    private fun muzzle(worm: Worm): Pair<Float, Float> {
        val rad = angleDeg * PI.toFloat() / 180f
        val mx = worm.x + cos(rad) * 24f
        val my = (worm.y - 14f) - sin(rad) * 24f
        return mx to my
    }

    private fun fire(powerValue: Float) {
        if (state != GameState.AIM && state != GameState.CPU_AIM) return
        val worm = currentWorm ?: return
        if (!worm.alive) return
        val (mx, my) = muzzle(worm)
        val rad = angleDeg * PI.toFloat() / 180f
        val speed = 4.5f + powerValue * 0.135f
        val vx = cos(rad) * speed
        val vy = -sin(rad) * speed
        when (weapon) {
            Weapon.BAZOOKA -> {
                projectile = Projectile(mx, my, vx, vy, Weapon.BAZOOKA)
                state = GameState.PROJECTILE
            }
            Weapon.GRENADE -> {
                projectile = Projectile(mx, my, vx, vy, Weapon.GRENADE, fuse = GRENADE_FUSE)
                state = GameState.PROJECTILE
            }
            Weapon.DYNAMITE -> {
                val dx = (worm.x + worm.facing * 12f).coerceIn(8f, WORLD_WIDTH - 8f)
                val dy = terrainInternal.groundAt(dx)
                dynamite = PlacedDynamite(dx, dy, DYNAMITE_FUSE)
                retreatTicks = RETREAT_TICKS
                state = GameState.RETREAT
            }
        }
        charging = false
        dragPower = null
        power = 0f
        onSound?.invoke(SoundEvent.SHOOT)
        onVibrate?.invoke(longArrayOf(25))
    }

    // ------------------------------------------------------------------
    // Dibujo (espacio lógico 1280x640; la vista aplica el escalado antes)
    // ------------------------------------------------------------------

    fun draw(canvas: Canvas) {
        drawFrame++
        canvas.save()
        if (shake > 0.3f) {
            val dx = (Random.Default.nextFloat() - 0.5f) * shake
            val dy = (Random.Default.nextFloat() - 0.5f) * shake
            canvas.translate(dx, dy)
        }
        drawSky(canvas)
        drawTerrain(canvas)
        drawWater(canvas)
        drawDecos(canvas)
        drawPreview(canvas)
        drawProjectileAndDynamite(canvas)
        for (worm in wormsInternal) {
            if (worm.alive) drawWorm(canvas, worm)
        }
        drawFloatingTexts(canvas)
        canvas.restore()
    }

    private fun drawSky(canvas: Canvas) {
        canvas.drawRect(0f, 0f, WORLD_WIDTH, WORLD_HEIGHT, skyPaint)
        // Sol con resplandor.
        canvas.drawCircle(1080f, 90f, 62f, sunGlowPaint)
        canvas.drawCircle(1080f, 90f, 38f, sunPaint)
        // Montañas lejanas.
        val far = Path()
        far.moveTo(0f, 420f)
        var x = 0f
        while (x <= WORLD_WIDTH) {
            val y = 330f + sin(x * 0.008f + 1f) * 42f + sin(x * 0.021f) * 12f
            far.lineTo(x, y)
            x += 32f
        }
        far.lineTo(WORLD_WIDTH, 640f)
        far.lineTo(0f, 640f)
        far.close()
        canvas.drawPath(far, mountainFarPaint)
        // Colinas cercanas.
        val near = Path()
        near.moveTo(0f, 470f)
        x = 0f
        while (x <= WORLD_WIDTH) {
            val y = 430f + sin(x * 0.012f + 3f) * 30f + sin(x * 0.03f + 1f) * 8f
            near.lineTo(x, y)
            x += 32f
        }
        near.lineTo(WORLD_WIDTH, 640f)
        near.lineTo(0f, 640f)
        near.close()
        canvas.drawPath(near, mountainNearPaint)
        // Nubes.
        for (cloud in clouds) {
            canvas.drawCircle(cloud.x, cloud.y, 26f * cloud.s, cloudPaint)
            canvas.drawCircle(cloud.x + 24f * cloud.s, cloud.y + 6f * cloud.s, 20f * cloud.s, cloudPaint)
            canvas.drawCircle(cloud.x - 26f * cloud.s, cloud.y + 8f * cloud.s, 18f * cloud.s, cloudPaint)
        }
        // Pájaros.
        for (bird in birds) {
            val s = bird.s
            val wing = 6f * s + sin(drawFrame * 0.12f + bird.x) * 2f
            val p = Path()
            p.moveTo(bird.x - 9f * s, bird.y - wing)
            p.quadTo(bird.x, bird.y + 3f * s, bird.x + 9f * s, bird.y - wing)
            canvas.drawPath(p, birdPaint)
        }
    }

    private fun drawTerrain(canvas: Canvas) {
        val path = Path()
        path.moveTo(0f, terrainInternal.heights[0])
        var x = 0
        while (x < terrainInternal.width) {
            path.lineTo(x.toFloat(), terrainInternal.heights[x])
            x += 4
        }
        path.lineTo((terrainInternal.width - 1).toFloat(), terrainInternal.heights[terrainInternal.width - 1])
        path.lineTo(WORLD_WIDTH, WORLD_HEIGHT)
        path.lineTo(0f, WORLD_HEIGHT)
        path.close()
        canvas.drawPath(path, terrainPaint)
        // Vetas de tierra.
        var vx = 40f
        while (vx < WORLD_WIDTH) {
            val gy = terrainInternal.groundAt(vx)
            canvas.drawLine(vx, gy + 14f, vx + 18f, gy + 26f, terrainVeinPaint)
            vx += 137f
        }
        // Pasto sobre la cresta.
        val grass = Path()
        grass.moveTo(0f, terrainInternal.heights[0])
        x = 0
        while (x < terrainInternal.width) {
            grass.lineTo(x.toFloat(), terrainInternal.heights[x])
            x += 4
        }
        canvas.drawPath(grass, grassPaint)
        canvas.drawPath(grass, grassLightPaint)
    }

    private fun drawWater(canvas: Canvas) {
        canvas.drawRect(0f, WATER_Y, WORLD_WIDTH, WORLD_HEIGHT, waterPaint)
        var x = 0f
        while (x < WORLD_WIDTH) {
            val y = WATER_Y + 5f + sin(x * 0.02f + drawFrame * 0.05f) * 1.5f
            canvas.drawLine(x, y, x + 22f, y, waterLinePaint)
            x += 46f
        }
    }

    private fun drawDecos(canvas: Canvas) {
        for (deco in decos) {
            val baseY = terrainInternal.groundAt(deco.x)
            when (deco.kind) {
                0 -> { // Arbolito.
                    val s = deco.s
                    canvas.drawRect(deco.x - 2.5f * s, baseY - 18f * s, deco.x + 2.5f * s, baseY, treeTrunkPaint)
                    canvas.drawCircle(deco.x, baseY - 26f * s, 13f * s, treeLeafPaint)
                    canvas.drawCircle(deco.x - 9f * s, baseY - 18f * s, 9f * s, treeLeafPaint)
                    canvas.drawCircle(deco.x + 9f * s, baseY - 18f * s, 9f * s, treeLeafPaint)
                }
                1 -> { // Roca.
                    val s = deco.s
                    val rect = RectF(deco.x - 10f * s, baseY - 8f * s, deco.x + 10f * s, baseY + 1f)
                    canvas.drawOval(rect, rockPaint)
                }
                2 -> { // Flor.
                    val s = deco.s
                    canvas.drawLine(deco.x, baseY, deco.x, baseY - 10f * s, grassLightPaint)
                    canvas.drawCircle(deco.x, baseY - 12f * s, 4f * s, flowerPaint)
                }
                else -> { // Mata.
                    val s = deco.s
                    canvas.drawCircle(deco.x - 5f * s, baseY - 4f * s, 6f * s, bushPaint)
                    canvas.drawCircle(deco.x + 5f * s, baseY - 4f * s, 6f * s, bushPaint)
                    canvas.drawCircle(deco.x, baseY - 7f * s, 7f * s, bushPaint)
                }
            }
        }
    }

    /** Preview de trayectoria: solo en AIM y nunca para dinamita. */
    private fun drawPreview(canvas: Canvas) {
        if (state != GameState.AIM || weapon == Weapon.DYNAMITE) return
        val worm = currentWorm ?: return
        if (!worm.alive) return
        val effPower = dragPower ?: if (charging) power else 55f
        val (mx, my) = muzzle(worm)
        val rad = angleDeg * PI.toFloat() / 180f
        val speed = 4.5f + effPower * 0.135f
        var x = mx
        var y = my
        var vx = cos(rad) * speed
        var vy = -sin(rad) * speed
        val windFactor = if (weapon == Weapon.GRENADE) 0.003f else 0.0075f
        var step = 0
        while (step < 46) {
            vy += 0.22f
            vx += wind * windFactor
            x += vx
            y += vy
            step++
            // La granada se corta al primer contacto con el suelo (sin rebote).
            if (weapon == Weapon.GRENADE && y >= terrainInternal.groundAt(x)) break
            if (step % 3 == 0) {
                val alpha = max(40, 220 - step * 4)
                previewOutlinePaint.alpha = alpha
                previewPaint.alpha = alpha
                canvas.drawCircle(x, y, 4.5f, previewOutlinePaint)
                canvas.drawCircle(x, y, 3.2f, previewPaint)
            }
        }
        previewOutlinePaint.alpha = 255
        previewPaint.alpha = 255
    }

    private fun drawProjectileAndDynamite(canvas: Canvas) {
        val p = projectile
        if (p != null) {
            val paint = if (p.weapon == Weapon.GRENADE) grenadePaint else projectilePaint
            canvas.drawCircle(p.x, p.y, if (p.weapon == Weapon.GRENADE) 7f else 6f, paint)
            canvas.drawCircle(p.x - 2f, p.y - 2f, 2f, eyeWhitePaint)
        }
        val d = dynamite
        if (d != null) {
            canvas.drawRect(d.x - 9f, d.y - 12f, d.x + 9f, d.y, dynamitePaint)
            canvas.drawLine(d.x, d.y - 12f, d.x + 4f, d.y - 18f, projectilePaint)
            val sparkR = 3f + if (drawFrame % 2L == 0L) 1.5f else 0f
            canvas.drawCircle(d.x + 4f, d.y - 18f, sparkR, sparkPaint)
        }
    }

    private fun drawWorm(canvas: Canvas, worm: Worm) {
        val cx = worm.x
        val cy = worm.y - 13f // Centro del cuerpo.
        val bodyColor = if (worm.team == Team.RED) RED_COLOR else BLUE_COLOR
        val darkColor = if (worm.team == Team.RED) RED_DARK else BLUE_DARK
        wormFillPaint.color = bodyColor
        wormOutlinePaint.color = darkColor

        // Indicador del gusano activo.
        if (worm === currentWorm && (state == GameState.AIM || state == GameState.RETREAT)) {
            val bob = sin(drawFrame * 0.1f) * 2f
            val tipY = cy - 46f + bob
            val tri = Path()
            tri.moveTo(cx - 7f, tipY - 8f)
            tri.lineTo(cx + 7f, tipY - 8f)
            tri.lineTo(cx, tipY)
            tri.close()
            canvas.drawPath(tri, indicatorPaint)
        }

        // Cuerpo.
        val body = RectF(cx - 15f, cy - 11f, cx + 15f, cy + 11f)
        canvas.drawOval(body, wormFillPaint)
        canvas.drawOval(body, wormOutlinePaint)
        // Casco.
        val helmetRect = RectF(cx - 14f, cy - 17f, cx + 14f, cy - 1f)
        canvas.drawArc(helmetRect, 180f, 180f, true, helmetPaint)
        canvas.drawArc(helmetRect, 180f, 180f, false, helmetDarkPaint)
        // Mejillas.
        canvas.drawCircle(cx - 9f, cy + 3f, 3f, cheekPaint)
        canvas.drawCircle(cx + 9f, cy + 3f, 3f, cheekPaint)
        // Ojos (miran hacia donde apunta el gusano).
        val eyeDx = worm.facing * 2f
        canvas.drawCircle(cx - 4f + eyeDx, cy - 4f, 3.5f, eyeWhitePaint)
        canvas.drawCircle(cx + 5f + eyeDx, cy - 4f, 3.5f, eyeWhitePaint)
        canvas.drawCircle(cx - 4f + eyeDx + worm.facing * 1.2f, cy - 4f, 1.8f, eyeBlackPaint)
        canvas.drawCircle(cx + 5f + eyeDx + worm.facing * 1.2f, cy - 4f, 1.8f, eyeBlackPaint)

        // Placa de nombre con el color del equipo.
        val textWidth = nameTextPaint.measureText(worm.name)
        val plateTop = cy - 40f
        nameBgPaint.color = darkColor
        val plate = RectF(
            cx - textWidth / 2f - 7f, plateTop,
            cx + textWidth / 2f + 7f, plateTop + 18f
        )
        canvas.drawRoundRect(plate, 9f, 9f, nameBgPaint)
        canvas.drawText(worm.name, cx, plateTop + 13.5f, nameTextPaint)
    }

    private fun drawFloatingTexts(canvas: Canvas) {
        for (t in floatingTexts) {
            val alpha = min(255, t.life * 4)
            floatOutlinePaint.alpha = alpha
            floatFillPaint.alpha = alpha
            floatFillPaint.color = t.color
            canvas.drawText(t.text, t.x, t.y, floatOutlinePaint)
            canvas.drawText(t.text, t.x, t.y, floatFillPaint)
        }
        floatOutlinePaint.alpha = 255
        floatFillPaint.alpha = 255
    }
}
