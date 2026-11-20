package com.guerradegusanos.app

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guerradegusanos.app.databinding.ActivityMainBinding
import com.guerradegusanos.app.game.Difficulty
import com.guerradegusanos.app.game.GameConfig
import com.guerradegusanos.app.game.GameEngine
import com.guerradegusanos.app.game.GameMode
import com.guerradegusanos.app.game.GameState
import com.guerradegusanos.app.game.SoundEvent
import com.guerradegusanos.app.game.SoundManager
import com.guerradegusanos.app.game.Team
import com.guerradegusanos.app.game.Weapon

/**
 * Actividad única de la app, con dos pantallas en el mismo layout:
 * el menú de configuración y la pantalla de juego.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var soundManager: SoundManager
    private var vibrator: Vibrator? = null

    private var engine: GameEngine? = null
    private var currentConfig: GameConfig? = null

    // Selección del menú.
    private var selectedMode: GameMode = GameMode.VS_CPU
    private var selectedWorms: Int = 3
    private var selectedDifficulty: Difficulty = Difficulty.NORMAL

    private var isFullscreen = false
    private var gameOverShown = false

    // Botones mantenidos (movimiento / apuntado): se repiten cada 16 ms.
    private val holdHandler = Handler(Looper.getMainLooper())
    private var moveDir = 0
    private var aimDir = 0
    private val holdRunnable = object : Runnable {
        override fun run() {
            val e = engine ?: return
            if (moveDir != 0) e.move(moveDir)
            if (aimDir != 0) e.adjustAim(aimDir * 0.9f)
            if (moveDir != 0 || aimDir != 0) {
                holdHandler.postDelayed(this, 16)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        soundManager = SoundManager(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        setupMenu()
        setupGameControls()
        refreshMenuSelection()
        updateSoundButton()
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private fun setupMenu() {
        binding.btnModeCpu.setOnClickListener {
            selectedMode = GameMode.VS_CPU
            soundManager.play(SoundEvent.CLICK)
            refreshMenuSelection()
        }
        binding.btnMode2p.setOnClickListener {
            selectedMode = GameMode.TWO_PLAYERS
            soundManager.play(SoundEvent.CLICK)
            refreshMenuSelection()
        }
        binding.btnWorms2.setOnClickListener { selectWorms(2) }
        binding.btnWorms3.setOnClickListener { selectWorms(3) }
        binding.btnWorms4.setOnClickListener { selectWorms(4) }
        binding.btnDiffEasy.setOnClickListener { selectDifficulty(Difficulty.EASY) }
        binding.btnDiffNormal.setOnClickListener { selectDifficulty(Difficulty.NORMAL) }
        binding.btnDiffHard.setOnClickListener { selectDifficulty(Difficulty.HARD) }

        binding.btnPlay.setOnClickListener {
            soundManager.play(SoundEvent.CLICK)
            startGame()
        }
    }

    private fun selectWorms(count: Int) {
        selectedWorms = count
        soundManager.play(SoundEvent.CLICK)
        refreshMenuSelection()
    }

    private fun selectDifficulty(difficulty: Difficulty) {
        selectedDifficulty = difficulty
        soundManager.play(SoundEvent.CLICK)
        refreshMenuSelection()
    }

    /** Pinta los selectores según la selección actual. La fila de
     *  dificultad solo se muestra en el modo contra la compu. */
    private fun refreshMenuSelection() {
        styleToggle(binding.btnModeCpu, selectedMode == GameMode.VS_CPU)
        styleToggle(binding.btnMode2p, selectedMode == GameMode.TWO_PLAYERS)
        styleToggle(binding.btnWorms2, selectedWorms == 2)
        styleToggle(binding.btnWorms3, selectedWorms == 3)
        styleToggle(binding.btnWorms4, selectedWorms == 4)
        styleToggle(binding.btnDiffEasy, selectedDifficulty == Difficulty.EASY)
        styleToggle(binding.btnDiffNormal, selectedDifficulty == Difficulty.NORMAL)
        styleToggle(binding.btnDiffHard, selectedDifficulty == Difficulty.HARD)
        binding.difficultyRow.visibility =
            if (selectedMode == GameMode.VS_CPU) View.VISIBLE else View.GONE
    }

    private fun styleToggle(button: Button, selected: Boolean) {
        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_primary)
            )
            button.setTextColor(ContextCompat.getColor(this, R.color.button_selected_text))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.button_default)
            )
            button.setTextColor(ContextCompat.getColor(this, R.color.text_dark))
        }
    }

    // ------------------------------------------------------------------
    // Juego: arranque y pantallas
    // ------------------------------------------------------------------

    private fun startGame() {
        val config = GameConfig(selectedMode, selectedWorms, selectedDifficulty)
        currentConfig = config
        gameOverShown = false
        val newEngine = GameEngine(config).apply {
            onSound = { event -> soundManager.play(event) }
            onVibrate = { pattern -> vibrate(pattern) }
        }
        engine = newEngine
        binding.gameView.onHudUpdate = { updateHud(it) }
        binding.gameView.engine = newEngine
        updateHud(newEngine)
        refreshWeaponSelection(newEngine.weapon)
        binding.menuScreen.visibility = View.GONE
        binding.gameScreen.visibility = View.VISIBLE
    }

    private fun showMenu() {
        holdHandler.removeCallbacks(holdRunnable)
        moveDir = 0
        aimDir = 0
        binding.gameView.engine = null
        engine = null
        binding.gameScreen.visibility = View.GONE
        binding.menuScreen.visibility = View.VISIBLE
        setFullscreen(false)
    }

    private fun restartGame() {
        val config = currentConfig ?: return
        gameOverShown = false
        soundManager.play(SoundEvent.CLICK)
        engine?.newGame(config)
        engine?.let { updateHud(it) }
    }

    // ------------------------------------------------------------------
    // Controles del juego
    // ------------------------------------------------------------------

    private fun setupGameControls() {
        binding.btnBackMenu.setOnClickListener {
            soundManager.play(SoundEvent.CLICK)
            showMenu()
        }
        binding.btnFullscreen.setOnClickListener {
            soundManager.play(SoundEvent.CLICK)
            setFullscreen(!isFullscreen)
        }
        binding.btnRestart.setOnClickListener { restartGame() }
        binding.btnSound.setOnClickListener {
            soundManager.enabled = !soundManager.enabled
            updateSoundButton()
            if (soundManager.enabled) soundManager.play(SoundEvent.CLICK)
        }

        // Movimiento: mantener pulsado.
        setHoldButton(binding.btnLeft, onDown = { moveDir = -1 }, onUp = { moveDir = 0 })
        setHoldButton(binding.btnRight, onDown = { moveDir = 1 }, onUp = { moveDir = 0 })
        binding.btnJump.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // El sonido de salto lo emite el motor (onSound) solo
                    // cuando el salto realmente ocurre.
                    engine?.jump()
                    true
                }
                else -> true
            }
        }

        // Apuntado: mantener pulsado.
        setHoldButton(binding.btnAimUp, onDown = { aimDir = 1 }, onUp = { aimDir = 0 })
        setHoldButton(binding.btnAimDown, onDown = { aimDir = -1 }, onUp = { aimDir = 0 })

        // Armas.
        binding.btnWeaponBazooka.setOnClickListener { selectWeapon(Weapon.BAZOOKA) }
        binding.btnWeaponGrenade.setOnClickListener { selectWeapon(Weapon.GRENADE) }
        binding.btnWeaponDynamite.setOnClickListener { selectWeapon(Weapon.DYNAMITE) }

        // Fuego: mantener para cargar, soltar para disparar.
        binding.btnFire.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    soundManager.play(SoundEvent.CLICK)
                    vibrate(longArrayOf(0, 30))
                    engine?.startCharging()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    engine?.releaseFire()
                    true
                }
                else -> true
            }
        }
    }

    /** Botón que actúa mientras se mantiene pulsado: al bajarlo arranca
     *  el repetidor y al soltarlo lo detiene. */
    private fun setHoldButton(button: Button, onDown: () -> Unit, onUp: () -> Unit) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onDown()
                    holdHandler.removeCallbacks(holdRunnable)
                    holdHandler.post(holdRunnable)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onUp()
                    if (moveDir == 0 && aimDir == 0) {
                        holdHandler.removeCallbacks(holdRunnable)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun selectWeapon(weapon: Weapon) {
        soundManager.play(SoundEvent.CLICK)
        engine?.selectWeapon(weapon)
        refreshWeaponSelection(weapon)
    }

    private fun refreshWeaponSelection(weapon: Weapon) {
        styleToggle(binding.btnWeaponBazooka, weapon == Weapon.BAZOOKA)
        styleToggle(binding.btnWeaponGrenade, weapon == Weapon.GRENADE)
        styleToggle(binding.btnWeaponDynamite, weapon == Weapon.DYNAMITE)
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private fun updateHud(e: GameEngine) {
        val totalHp = e.config.wormsPerTeam * 100
        val redHp = e.worms.filter { it.team == Team.RED && it.alive }.sumOf { it.hp }
        val blueHp = e.worms.filter { it.team == Team.BLUE && it.alive }.sumOf { it.hp }

        binding.hpRedBar.max = totalHp
        binding.hpRedBar.progress = redHp
        binding.hpBlueBar.max = totalHp
        binding.hpBlueBar.progress = blueHp
        binding.hpRedText.text = getString(R.string.hp_red, redHp)
        binding.hpBlueText.text = getString(R.string.hp_blue, blueHp)

        val worm = e.currentWorm
        binding.turnText.text = if (worm != null) {
            val teamName = if (worm.team == Team.RED) {
                getString(R.string.turn_red)
            } else {
                getString(R.string.turn_blue)
            }
            getString(R.string.turn_format, "${worm.name} · $teamName")
        } else {
            getString(R.string.turn_format, "")
        }

        // El temporizador solo corre en estado AIM; se muestran los
        // segundos restantes (timeLeftTicks / 60).
        val seconds = (e.timeLeftTicks / 60).coerceAtLeast(0)
        binding.timeText.text = getString(R.string.time_format, seconds)

        val wind = e.wind
        binding.windText.text = when {
            wind == 0 -> getString(R.string.wind_none)
            wind > 0 -> getString(R.string.wind_format, "▶".repeat(wind.coerceAtMost(10)), wind)
            else -> getString(R.string.wind_format, "◀".repeat((-wind).coerceAtMost(10)), -wind)
        }

        binding.statusText.text = e.statusMessage.ifBlank {
            getString(R.string.status_default)
        }

        if (e.state == GameState.OVER && !gameOverShown) {
            gameOverShown = true
            showGameOverDialog(e)
        }
    }

    // ------------------------------------------------------------------
    // Fin de partida
    // ------------------------------------------------------------------

    private fun showGameOverDialog(e: GameEngine) {
        val message = when {
            e.isDraw -> getString(R.string.draw)
            e.winner == Team.RED -> getString(R.string.win_red)
            e.winner == Team.BLUE -> getString(R.string.win_blue)
            else -> getString(R.string.draw)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.game_over_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.rematch) { dialog, _ ->
                gameOverShown = false
                currentConfig?.let { engine?.newGame(it) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back_to_menu) { dialog, _ ->
                dialog.dismiss()
                showMenu()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Pantalla completa y sonido
    // ------------------------------------------------------------------

    private fun setFullscreen(full: Boolean) {
        isFullscreen = full
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (full) {
                controller?.hide(WindowInsets.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller?.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (full) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) setFullscreen(true)
    }

    private fun updateSoundButton() {
        binding.btnSound.text = if (soundManager.enabled) "🔊" else "🔇"
        binding.btnSound.contentDescription = getString(
            if (soundManager.enabled) R.string.sound_on else R.string.sound_off
        )
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    override fun onDestroy() {
        holdHandler.removeCallbacks(holdRunnable)
        binding.gameView.engine = null
        soundManager.release()
        super.onDestroy()
    }
}
