package com.aryan.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    Idle,
    Listening,
    Speaking,
    Thinking,
    Active
}

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentState: OrbState = OrbState.Idle
    private var amplitude: Float = 0f

    // Animations
    private var pulseScale: Float = 1.0f
    private var glowAlpha: Int = 140
    private var rotationAngle: Float = 0f
    private var waveOffset: Float = 0f
    private var thinkingAngle: Float = 0f
    private var particleAngle: Float = 0f

    private var pulseAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null
    private var thinkingAnimator: ValueAnimator? = null

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(30f, 20f), 0f)
    }
    private val ringPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(15f, 25f), 0f)
    }
    private val ringPaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(40f, 15f), 0f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringRect1 = RectF()
    private val ringRect2 = RectF()
    private val ringRect3 = RectF()
    private val thinkingRect = RectF()
    private val wavePath = Path()

    init {
        startAnimators()
    }

    private fun startAnimators() {
        // 1. Pulse Animator
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                pulseScale = anim.animatedValue as Float
                glowAlpha = (120 + (pulseScale - 1.0f) * 666).toInt().coerceIn(120, 220)
                invalidate()
            }
            start()
        }

        // 2. Rotation Animator
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                rotationAngle = anim.animatedValue as Float
                particleAngle = (particleAngle + 1.2f) % 360f
                invalidate()
            }
            start()
        }

        // 3. Wave Animator
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 2000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                waveOffset = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 4. Thinking Animator
        thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                thinkingAngle = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setState(state: OrbState) {
        if (this.currentState != state) {
            this.currentState = state
            invalidate()
        }
    }

    fun setAmplitude(amp: Float) {
        this.amplitude = amp.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.52f
        val ampBonus = amplitude * baseRadius * 0.4f
        val currentRadius = (baseRadius * pulseScale + ampBonus).coerceAtLeast(10f)

        // Determine colors according to state
        val (coreStart, coreEnd, ringColor) = when (currentState) {
            OrbState.Idle -> Triple(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"), Color.parseColor("#880E4F"))
            OrbState.Listening -> Triple(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"), Color.parseColor("#FF1744"))
            OrbState.Speaking -> Triple(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"), Color.parseColor("#E040FB"))
            OrbState.Thinking -> Triple(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"), Color.parseColor("#40C4FF"))
            OrbState.Active -> Triple(Color.parseColor("#FF1744"), Color.parseColor("#9C27B0"), Color.parseColor("#FF1744"))
        }

        // LAYER 1: Radial Glow (1.6x radius)
        val glowRadius = currentRadius * 1.6f
        val glowColor = Color.argb(
            glowAlpha,
            Color.red(coreStart),
            Color.green(coreStart),
            Color.blue(coreStart)
        )
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(glowColor, Color.TRANSPARENT),
            floatArrayOf(0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // LAYER 2: Core Orb (Sphere illusion)
        corePaint.shader = RadialGradient(
            cx - currentRadius * 0.25f, cy - currentRadius * 0.25f, currentRadius * 1.2f,
            intArrayOf(coreStart, coreEnd, Color.parseColor("#080004")),
            floatArrayOf(0f, 0.7f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, currentRadius, corePaint)

        // LAYER 3: 3 Rotating Rings (Dashed arcs)
        ringPaint1.color = ringColor
        ringPaint2.color = Color.parseColor("#D500F9")
        ringPaint3.color = Color.parseColor("#FF1744")

        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        ringRect1.set(cx - currentRadius * 1.18f, cy - currentRadius * 1.18f, cx + currentRadius * 1.18f, cy + currentRadius * 1.18f)
        canvas.drawOval(ringRect1, ringPaint1)
        canvas.restore()

        canvas.save()
        canvas.rotate(-rotationAngle * 1.3f, cx, cy)
        ringRect2.set(cx - currentRadius * 1.32f, cy - currentRadius * 1.32f, cx + currentRadius * 1.32f, cy + currentRadius * 1.32f)
        canvas.drawOval(ringRect2, ringPaint2)
        canvas.restore()

        canvas.save()
        canvas.rotate(rotationAngle * 0.7f, cx, cy)
        ringRect3.set(cx - currentRadius * 1.45f, cy - currentRadius * 1.45f, cx + currentRadius * 1.45f, cy + currentRadius * 1.45f)
        canvas.drawOval(ringRect3, ringPaint3)
        canvas.restore()

        // LAYER 4: Wave Rings (Sine waves with amplitude)
        if (currentState != OrbState.Idle) {
            wavePaint.color = Color.argb(160, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor))
            wavePath.reset()
            val waveR = currentRadius * 1.1f
            val segments = 72
            for (i in 0..segments) {
                val theta = (i.toFloat() / segments) * 2 * Math.PI.toFloat()
                val distortion = sin((theta * 6 + waveOffset).toDouble()).toFloat() * (6f + amplitude * 18f)
                val r = waveR + distortion
                val x = cx + r * cos(theta.toDouble()).toFloat()
                val y = cy + r * sin(theta.toDouble()).toFloat()
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)
        }

        // LAYER 5: Thinking Arc (2 arcs spinning, only in Thinking state)
        if (currentState == OrbState.Thinking) {
            thinkingPaint.color = Color.parseColor("#40C4FF")
            thinkingRect.set(cx - currentRadius * 1.25f, cy - currentRadius * 1.25f, cx + currentRadius * 1.25f, cy + currentRadius * 1.25f)
            canvas.drawArc(thinkingRect, thinkingAngle, 80f, false, thinkingPaint)
            canvas.drawArc(thinkingRect, thinkingAngle + 180f, 80f, false, thinkingPaint)
        }

        // LAYER 6: Orbiting Particles (12 dots, active/speaking/listening)
        if (currentState != OrbState.Idle) {
            particlePaint.color = Color.argb(200, 255, 255, 255)
            val numParticles = 12
            for (i in 0 until numParticles) {
                val pAngle = Math.toRadians((particleAngle + i * (360f / numParticles)).toDouble())
                val pDistance = currentRadius * (1.15f + (i % 3) * 0.12f)
                val px = cx + pDistance * cos(pAngle).toFloat()
                val py = cy + pDistance * sin(pAngle).toFloat()
                val pRadius = if (i % 2 == 0) 3.5f else 2f
                canvas.drawCircle(px, py, pRadius + amplitude * 2.5f, particlePaint)
            }
        }

        // LAYER 7: Inner Highlight (White radial gradient top-left)
        val hlRadius = currentRadius * 0.45f
        val hlX = cx - currentRadius * 0.35f
        val hlY = cy - currentRadius * 0.35f
        highlightPaint.shader = RadialGradient(
            hlX, hlY, hlRadius,
            intArrayOf(Color.argb(160, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(hlX, hlY, hlRadius, highlightPaint)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        rotationAnimator?.cancel()
        waveAnimator?.cancel()
        thinkingAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
