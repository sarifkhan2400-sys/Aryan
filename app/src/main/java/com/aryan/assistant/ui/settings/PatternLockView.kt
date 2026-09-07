package com.aryan.assistant.ui.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnPatternListener {
        fun onPatternDetected(pattern: List<Int>)
        fun onPatternCleared()
    }

    var listener: OnPatternListener? = null
    var isReadOnly: Boolean = false

    private val selectedNodes = mutableListOf<Int>()
    private var isDragging = false
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isErrorState = false

    // Node coordinates cache
    private val nodePoints = Array(9) { FloatArray(2) }

    // Colors & Paints
    private val dotNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#333333")
    }

    private val dotCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#888888")
    }

    private val dotSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1744")
    }

    private val dotSelectedCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#FF1744")
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FF1744")
    }

    private val linePath = Path()

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width
        } else {
            min(width, height)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateNodePositions(w.toFloat(), h.toFloat())
    }

    private fun calculateNodePositions(width: Float, height: Float) {
        val padding = width * 0.16f
        val usableWidth = width - (padding * 2f)
        val step = usableWidth / 2f

        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            nodePoints[i][0] = padding + (col * step)
            nodePoints[i][1] = padding + (row * step)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val baseRadius = 10f * density
        val outerRingRadius = 24f * density
        val centerDotRadius = 4f * density

        // 1. Draw Connecting Lines
        if (selectedNodes.isNotEmpty()) {
            linePath.reset()
            val first = selectedNodes.first()
            linePath.moveTo(nodePoints[first][0], nodePoints[first][1])

            for (i in 1 until selectedNodes.size) {
                val node = selectedNodes[i]
                linePath.lineTo(nodePoints[node][0], nodePoints[node][1])
            }

            // Draw line to current touch position if actively dragging
            if (isDragging && !isReadOnly) {
                linePath.lineTo(currentTouchX, currentTouchY)
            }

            val strokeColor = if (isErrorState) Color.parseColor("#D50000") else Color.parseColor("#FF1744")
            linePaint.color = strokeColor
            canvas.drawPath(linePath, linePaint)
        }

        // 2. Draw 3x3 Nodes
        for (i in 0 until 9) {
            val cx = nodePoints[i][0]
            val cy = nodePoints[i][1]
            val isSelected = selectedNodes.contains(i)

            if (isSelected) {
                val ringColor = if (isErrorState) Color.parseColor("#D50000") else Color.parseColor("#FF1744")
                dotRingPaint.color = ringColor
                dotRingPaint.alpha = 180
                canvas.drawCircle(cx, cy, outerRingRadius, dotRingPaint)

                dotSelectedPaint.color = ringColor
                canvas.drawCircle(cx, cy, baseRadius, dotSelectedPaint)
                canvas.drawCircle(cx, cy, centerDotRadius, dotSelectedCenterPaint)
            } else {
                canvas.drawCircle(cx, cy, baseRadius, dotNormalPaint)
                canvas.drawCircle(cx, cy, centerDotRadius, dotCenterPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isReadOnly || !isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                clearPatternInternal()
                isErrorState = false
                val hitNode = findHitNode(event.x, event.y)
                if (hitNode != -1) {
                    selectedNodes.add(hitNode)
                    isDragging = true
                    currentTouchX = event.x
                    currentTouchY = event.y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    currentTouchX = event.x
                    currentTouchY = event.y

                    val hitNode = findHitNode(event.x, event.y)
                    if (hitNode != -1 && !selectedNodes.contains(hitNode)) {
                        selectedNodes.add(hitNode)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    invalidate()
                    if (selectedNodes.isNotEmpty()) {
                        listener?.onPatternDetected(selectedNodes.toList())
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findHitNode(x: Float, y: Float): Int {
        val density = resources.displayMetrics.density
        val hitRadius = 32f * density

        for (i in 0 until 9) {
            val cx = nodePoints[i][0]
            val cy = nodePoints[i][1]
            val dx = x - cx
            val dy = y - cy
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= hitRadius) {
                return i
            }
        }
        return -1
    }

    fun clearPattern() {
        clearPatternInternal()
        listener?.onPatternCleared()
        invalidate()
    }

    private fun clearPatternInternal() {
        selectedNodes.clear()
        isErrorState = false
        isDragging = false
    }

    fun setPattern(pattern: List<Int>) {
        selectedNodes.clear()
        selectedNodes.addAll(pattern)
        isErrorState = false
        isDragging = false
        invalidate()
    }

    fun getPattern(): List<Int> = selectedNodes.toList()

    fun setError(error: Boolean) {
        isErrorState = error
        invalidate()
    }
}
