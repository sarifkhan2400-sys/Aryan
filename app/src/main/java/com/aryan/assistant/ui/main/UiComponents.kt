package com.aryan.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import java.util.Random

// ---------------- Waveform View ----------------
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val numBars = 20
    private val currentHeights = FloatArray(numBars) { 4f }
    private val targetHeights = FloatArray(numBars) { 4f }
    private val random = Random()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()
    private var animator: ValueAnimator? = null
    private var baseAmplitude = 0f

    init {
        startAnimation()
    }

    fun setAmplitude(rms: Float) {
        baseAmplitude = rms.coerceIn(0f, 1f)
        for (i in 0 until numBars) {
            val factor = 0.3f + random.nextFloat() * 0.7f
            targetHeights[i] = (baseAmplitude * factor * (height * 0.9f)).coerceAtLeast(6f)
        }
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 50
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (i in 0 until numBars) {
                    currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.35f
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        for (i in 0 until numBars) {
            targetHeights[i] = 4f
            currentHeights[i] = 4f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val spacing = 4f
        val totalSpacing = spacing * (numBars - 1)
        val barWidth = ((width - totalSpacing) / numBars).coerceAtLeast(2f)
        val centerY = height / 2f

        for (i in 0 until numBars) {
            val x = i * (barWidth + spacing)
            val barH = currentHeights[i].coerceIn(4f, height.toFloat())
            val top = centerY - (barH / 2f)
            val bottom = centerY + (barH / 2f)

            val alphaFraction = (barH / height.toFloat()).coerceIn(0f, 1f)
            val alpha = (140 + alphaFraction * 115).toInt().coerceIn(140, 255)
            barPaint.color = Color.argb(alpha, 255, 23, 68)

            barRect.set(x, top, x + barWidth, bottom)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

// ---------------- Chat Message Data ----------------
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ---------------- Chat Recycler Adapter ----------------
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_ARYAN = 2
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastAryanMessage(text: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            val lastIdx = messages.size - 1
            messages[lastIdx] = messages[lastIdx].copy(text = text)
            notifyItemChanged(lastIdx)
        } else {
            addMessage(ChatMessage(text = text, isUser = false))
        }
    }

    fun lastAryanText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    fun clearMessages() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_ARYAN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_aryan, parent, false)
            AryanViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.text.text = msg.text
        } else if (holder is AryanViewHolder) {
            holder.text.text = msg.text
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.userMessageText)
    }

    class AryanViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.aryanMessageText)
    }
}
