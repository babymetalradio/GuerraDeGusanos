package com.guerradegusanos.app.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.guerradegusanos.app.game.GameEngine
import com.guerradegusanos.app.game.GameState
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Vista del campo de batalla.
 *
 * El espacio lógico del juego es 1280x640. Esta vista calcula la escala
 * necesaria para encajarlo en su tamaño real, centra el mundo y aplica la
 * transformación al canvas antes de delegar el dibujo al motor
 * ([GameEngine.draw]). También corre el bucle de juego a 60 ticks/s y
 * traduce los arrastres del dedo en ángulo/potencia de disparo.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOGICAL_WIDTH = 1280f
        const val LOGICAL_HEIGHT = 640f
        private const val TICK_MS = 16L
    }

    /** Se invoca en cada tick con el motor, para refrescar el HUD. */
    var onHudUpdate: ((GameEngine) -> Unit)? = null

    /** Motor del juego. Al asignarlo (no nulo) arranca el bucle; al ponerlo
     *  en nulo se detiene. */
    var engine: GameEngine? = null
        set(value) {
            if (field === value) return
            field = value
            if (value == null) {
                stopLoop()
            } else if (isAttachedToWindow && visibility == VISIBLE) {
                startLoop()
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var loopRunning = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            val e = engine
            if (e == null || !loopRunning) return
            if (visibility == VISIBLE) {
                e.update()
                onHudUpdate?.invoke(e)
                invalidate()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Escala y desplazamiento actuales del mundo lógico a la vista. */
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private fun computeTransform() {
        scale = minOf(width / LOGICAL_WIDTH, height / LOGICAL_HEIGHT)
        offsetX = (width - LOGICAL_WIDTH * scale) / 2f
        offsetY = (height - LOGICAL_HEIGHT * scale) / 2f
    }

    fun startLoop() {
        if (loopRunning) return
        loopRunning = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    fun stopLoop() {
        loopRunning = false
        handler.removeCallbacks(tickRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (engine != null && visibility == VISIBLE) startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && engine != null) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return
        computeTransform()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        e.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = engine ?: return false
        if (e.state != GameState.AIM) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val worm = e.currentWorm ?: return false
                computeTransform()
                // Coordenadas lógicas del toque.
                val tx = (event.x - offsetX) / scale
                val ty = (event.y - offsetY) / scale
                // Centro del gusano activo (un poco por encima de su base).
                val wx = worm.x
                val wy = worm.y - 14f
                val dx = tx - wx
                val dy = -(ty - wy) // Y lógica hacia arriba.
                // Ángulo: 0° = derecha, 90° = arriba, 180° = izquierda.
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                if (angle < 0) angle += 360.0
                if (angle > 180) angle = 360.0 - angle
                angle = angle.coerceIn(3.0, 177.0)
                // Potencia según la distancia del toque al gusano.
                val dist = hypot((tx - wx).toDouble(), (ty - wy).toDouble())
                val power = ((dist - 30.0) / 2.6).coerceIn(5.0, 100.0)
                e.dragAim(angle.toFloat(), power.toFloat())
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
